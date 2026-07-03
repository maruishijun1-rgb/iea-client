'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const AdmZip = require('adm-zip');

const { downloadFile } = require('./http');
const { detectJavaMajor } = require('./java');

// Adoptium (Eclipse Temurin) is a free, redistributable OpenJDK build with a
// simple API. We fetch the latest GA Java 8 JRE for the current OS/arch.
function adoptiumOs() {
  switch (process.platform) {
    case 'win32': return 'windows';
    case 'darwin': return 'mac';
    default: return 'linux';
  }
}
function adoptiumArch() {
  switch (process.arch) {
    case 'x64': return 'x64';
    case 'arm64': return 'aarch64';
    case 'ia32': return 'x86';
    default: return 'x64';
  }
}
function binaryUrl(os, arch) {
  return `https://api.adoptium.net/v3/binary/latest/8/ga/${os}/${arch}/jre/hotspot/normal/eclipse`;
}

// Recursively find a .../bin/java(.exe) under dir.
function findJavaBinary(dir) {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';
  if (!fs.existsSync(dir)) return null;
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries;
    try { entries = fs.readdirSync(cur, { withFileTypes: true }); } catch (_) { continue; }
    for (const e of entries) {
      const full = path.join(cur, e.name);
      if (e.isDirectory()) stack.push(full);
      else if (e.name === exe && path.basename(path.dirname(full)) === 'bin') return full;
    }
  }
  return null;
}

function extractZip(zipPath, destDir) {
  const zip = new AdmZip(zipPath);
  for (const entry of zip.getEntries()) {
    const out = path.join(destDir, entry.entryName);
    if (entry.isDirectory) { fs.mkdirSync(out, { recursive: true }); continue; }
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, entry.getData());
  }
}

function extractTarGz(archive, destDir) {
  return new Promise((resolve, reject) => {
    const p = spawn('tar', ['-xzf', archive, '-C', destDir]);
    p.on('error', reject);
    p.on('close', (code) => (code === 0 ? resolve() : reject(new Error('tar exited ' + code))));
  });
}

/**
 * Ensure a Java 8 runtime is available, downloading Temurin JRE 8 into
 * <userDataDir>/runtime/jre8 if needed. Returns the absolute path to the java
 * executable. `emit(type, payload)` reports status/progress to the UI.
 */
async function ensureJava8(userDataDir, emit = () => {}) {
  const runtimeRoot = path.join(userDataDir, 'runtime');
  const dir = path.join(runtimeRoot, 'jre8');

  // Reuse an existing valid install.
  const existing = findJavaBinary(dir);
  if (existing) {
    const major = await detectJavaMajor(existing);
    if (major === 8) return existing;
  }

  const os = adoptiumOs();
  const ext = os === 'windows' ? 'zip' : 'tar.gz';
  const archivePath = path.join(runtimeRoot, `jre8-download.${ext}`);
  fs.mkdirSync(runtimeRoot, { recursive: true });

  const onProgress = (received, total) => {
    if (total) {
      const pct = Math.round((received / total) * 100);
      emit('status', `Downloading Java 8 runtime… ${pct}%`);
    } else {
      emit('status', `Downloading Java 8 runtime… ${(received / 1048576).toFixed(0)} MB`);
    }
  };

  emit('status', 'Downloading Java 8 runtime…');
  let arch = adoptiumArch();
  try {
    await downloadFile(binaryUrl(os, arch), archivePath, null, onProgress);
  } catch (e) {
    // Fall back to x64 (works under Rosetta on Apple Silicon) if native arch is missing.
    if (arch !== 'x64') {
      arch = 'x64';
      await downloadFile(binaryUrl(os, arch), archivePath, null, onProgress);
    } else {
      throw e;
    }
  }

  emit('status', 'Extracting Java 8…');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  if (ext === 'zip') extractZip(archivePath, dir);
  else await extractTarGz(archivePath, dir);
  fs.rmSync(archivePath, { force: true });

  const bin = findJavaBinary(dir);
  if (!bin) throw new Error('Java 8 was downloaded but the java executable could not be located.');
  // Ensure it is executable on unix (tar usually preserves this).
  if (process.platform !== 'win32') {
    try { fs.chmodSync(bin, 0o755); } catch (_) {}
  }
  return bin;
}

module.exports = { ensureJava8, findJavaBinary };
