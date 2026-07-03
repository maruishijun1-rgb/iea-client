'use strict';

const fs = require('fs');
const path = require('path');
const { getJSON, downloadFile } = require('./http');

const RESOURCE_BASE = 'https://resources.download.minecraft.net';

/**
 * Ensure the asset index JSON is present, then return the list of asset objects
 * that need downloading as [{ url, dest, sha1 }].
 */
async function resolveAssets(versionJson, paths) {
  const assetIndex = versionJson.assetIndex;
  if (!assetIndex) return { indexId: null, downloads: [] };

  const indexPath = path.join(paths.assetIndexes, assetIndex.id + '.json');
  fs.mkdirSync(path.dirname(indexPath), { recursive: true });
  await downloadFile(assetIndex.url, indexPath, assetIndex.sha1 || null);

  const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
  const downloads = [];
  for (const key of Object.keys(index.objects || {})) {
    const hash = index.objects[key].hash;
    const sub = hash.substring(0, 2);
    const dest = path.join(paths.assetObjects, sub, hash);
    const url = `${RESOURCE_BASE}/${sub}/${hash}`;
    downloads.push({ url, dest, sha1: hash });
  }
  return { indexId: assetIndex.id, downloads };
}

module.exports = { resolveAssets };
