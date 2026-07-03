'use strict';

const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');

function isExcluded(entryName, excludes) {
  return excludes.some((ex) => entryName.startsWith(ex));
}

/**
 * Extract the OS-native libraries (.dll/.dylib/.so) from each native jar into
 * the per-version natives directory. Skips excluded paths (e.g. META-INF/).
 */
function extractNatives(nativeJars, destDir) {
  fs.mkdirSync(destDir, { recursive: true });
  for (const { jar, exclude } of nativeJars) {
    if (!fs.existsSync(jar)) continue;
    const zip = new AdmZip(jar);
    for (const entry of zip.getEntries()) {
      if (entry.isDirectory) continue;
      if (isExcluded(entry.entryName, exclude || ['META-INF/'])) continue;
      const outPath = path.join(destDir, path.basename(entry.entryName));
      fs.writeFileSync(outPath, entry.getData());
    }
  }
}

module.exports = { extractNatives };
