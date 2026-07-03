'use strict';

const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');

const MAX_REDIRECTS = 5;

function pick(urlStr) {
  return urlStr.startsWith('https:') ? https : http;
}

/**
 * GET a URL and return the raw response body as a Buffer.
 * Follows redirects.
 */
function getBuffer(urlStr, headers = {}, redirects = 0) {
  return new Promise((resolve, reject) => {
    const lib = pick(urlStr);
    const req = lib.get(urlStr, { headers }, (res) => {
      const status = res.statusCode || 0;
      if (status >= 300 && status < 400 && res.headers.location) {
        if (redirects >= MAX_REDIRECTS) {
          reject(new Error('Too many redirects: ' + urlStr));
          return;
        }
        const next = new URL(res.headers.location, urlStr).toString();
        res.resume();
        resolve(getBuffer(next, headers, redirects + 1));
        return;
      }
      if (status < 200 || status >= 300) {
        res.resume();
        reject(new Error(`HTTP ${status} for ${urlStr}`));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    });
    req.on('error', reject);
  });
}

async function getJSON(urlStr, headers = {}) {
  const buf = await getBuffer(urlStr, headers);
  return JSON.parse(buf.toString('utf8'));
}

function postJSON(urlStr, body, headers = {}, redirects = 0) {
  return new Promise((resolve, reject) => {
    const lib = pick(urlStr);
    const data = typeof body === 'string' ? body : JSON.stringify(body);
    const u = new URL(urlStr);
    const opts = {
      method: 'POST',
      hostname: u.hostname,
      path: u.pathname + u.search,
      headers: Object.assign(
        {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
          'Content-Length': Buffer.byteLength(data),
        },
        headers
      ),
    };
    const req = lib.request(opts, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        const status = res.statusCode || 0;
        let parsed = null;
        try {
          parsed = text ? JSON.parse(text) : null;
        } catch (_) {
          parsed = text;
        }
        if (status < 200 || status >= 300) {
          const err = new Error(`HTTP ${status} for ${urlStr}: ${text}`);
          err.status = status;
          err.body = parsed;
          reject(err);
          return;
        }
        resolve(parsed);
      });
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

function postForm(urlStr, formObj, headers = {}) {
  const params = new URLSearchParams(formObj).toString();
  return postJSON(
    urlStr,
    params,
    Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, headers)
  );
}

function sha1OfFile(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha1');
    const stream = fs.createReadStream(filePath);
    stream.on('error', reject);
    stream.on('data', (d) => hash.update(d));
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

/**
 * Download a file to dest, following redirects and streaming to disk.
 * If expectedSha1 is provided and an existing file matches, the download is skipped.
 */
async function downloadFile(urlStr, dest, expectedSha1 = null, onProgress = null) {
  if (expectedSha1 && fs.existsSync(dest)) {
    try {
      const have = await sha1OfFile(dest);
      if (have === expectedSha1) return false; // already valid
    } catch (_) {
      /* fall through and re-download */
    }
  }
  fs.mkdirSync(path.dirname(dest), { recursive: true });

  await new Promise((resolve, reject) => {
    const doRequest = (u, redirects) => {
      const lib = pick(u);
      const req = lib.get(u, (res) => {
        const status = res.statusCode || 0;
        if (status >= 300 && status < 400 && res.headers.location) {
          if (redirects >= MAX_REDIRECTS) {
            reject(new Error('Too many redirects: ' + u));
            return;
          }
          const next = new URL(res.headers.location, u).toString();
          res.resume();
          doRequest(next, redirects + 1);
          return;
        }
        if (status < 200 || status >= 300) {
          res.resume();
          reject(new Error(`HTTP ${status} for ${u}`));
          return;
        }
        const total = parseInt(res.headers['content-length'] || '0', 10);
        let received = 0;
        if (onProgress) {
          res.on('data', (chunk) => {
            received += chunk.length;
            onProgress(received, total);
          });
        }
        const out = fs.createWriteStream(dest);
        res.pipe(out);
        out.on('finish', () => out.close(resolve));
        out.on('error', reject);
      });
      req.on('error', reject);
    };
    doRequest(urlStr, 0);
  });

  if (expectedSha1) {
    const have = await sha1OfFile(dest);
    if (have !== expectedSha1) {
      throw new Error(`SHA1 mismatch for ${dest}: expected ${expectedSha1}, got ${have}`);
    }
  }
  return true; // downloaded
}

/**
 * Run async tasks with limited concurrency. onProgress(done, total) is called as each finishes.
 */
async function pool(items, worker, concurrency = 8, onProgress = null) {
  let index = 0;
  let done = 0;
  const total = items.length;
  const results = new Array(total);
  async function runner() {
    while (index < total) {
      const i = index++;
      results[i] = await worker(items[i], i);
      done++;
      if (onProgress) onProgress(done, total);
    }
  }
  const runners = [];
  for (let i = 0; i < Math.min(concurrency, total); i++) runners.push(runner());
  await Promise.all(runners);
  return results;
}

module.exports = {
  getBuffer,
  getJSON,
  postJSON,
  postForm,
  downloadFile,
  sha1OfFile,
  pool,
};
