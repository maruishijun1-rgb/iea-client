'use strict';

const path = require('path');
const fs = require('fs');
const os = require('os');

/**
 * Build the set of game data paths. Everything is kept self-contained under the
 * launcher's userData directory so uninstalling is clean. The caller passes in
 * Electron's app.getPath('userData').
 */
function makePaths(userDataDir) {
  const root = path.join(userDataDir, 'game');
  const versions = path.join(root, 'versions');
  const versionDir = (id) => path.join(versions, id);

  return {
    root,
    versions,
    libraries: path.join(root, 'libraries'),
    // drop an OptiFine_1.8.9_HD_U_*.jar here to enable OptiFine (custom sky, etc.)
    optifine: path.join(root, 'optifine'),
    assets: path.join(root, 'assets'),
    assetIndexes: path.join(root, 'assets', 'indexes'),
    assetObjects: path.join(root, 'assets', 'objects'),
    natives: path.join(root, 'natives'),
    // working directory the game runs in (saves, options.txt, screenshots, ...)
    gameDir: path.join(root, 'instances', 'default'),
    versionDir,
    versionJar: (id) => path.join(versionDir(id), id + '.jar'),
    versionJson: (id) => path.join(versionDir(id), id + '.json'),
    nativesDir: (id) => path.join(root, 'natives', id),
  };
}

function ensureDirs(p) {
  for (const key of ['root', 'versions', 'libraries', 'optifine', 'assets', 'assetIndexes', 'assetObjects', 'natives', 'gameDir']) {
    fs.mkdirSync(p[key], { recursive: true });
  }
}

module.exports = { makePaths, ensureDirs, homeDir: os.homedir() };
