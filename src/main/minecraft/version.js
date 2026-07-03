'use strict';

const fs = require('fs');
const path = require('path');
const { getJSON, downloadFile } = require('./http');

const MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';

/**
 * Fetch the global version manifest and return the entry for the given version id.
 */
async function findVersionEntry(versionId) {
  const manifest = await getJSON(MANIFEST_URL);
  const entry = manifest.versions.find((v) => v.id === versionId);
  if (!entry) throw new Error(`Version "${versionId}" not found in Mojang manifest.`);
  return entry;
}

/**
 * Ensure the version JSON (e.g. 1.8.9.json) is present locally; download it if not.
 * Returns the parsed version JSON.
 */
async function ensureVersionJson(versionId, paths) {
  const jsonPath = paths.versionJson(versionId);
  if (fs.existsSync(jsonPath)) {
    return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  }
  const entry = await findVersionEntry(versionId);
  fs.mkdirSync(path.dirname(jsonPath), { recursive: true });
  await downloadFile(entry.url, jsonPath, entry.sha1 || null);
  return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
}

module.exports = { MANIFEST_URL, findVersionEntry, ensureVersionJson };
