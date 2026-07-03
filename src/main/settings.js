'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  versionId: '1.8.9',
  language: 'ja',      // UI language: 'ja' | 'en'
  javaPath: '',        // empty = use "java" from PATH
  gameDir: '',         // empty = default location under userData
  maxRamMB: 2048,
  minRamMB: 512,
  injectClient: true, // inject the IEA Java agent (the modified-client features)
  capLog: true,       // cap the launcher log console to recent lines (saves memory)
  hypixelKey: '',     // Hypixel API key -> written to iea-hypixel-key.txt for LevelHead
  optimizeJvm: true,  // apply G1GC performance flags + first-run perf options.txt
  useOptifine: false, // OptiFine integration disabled (IEA + OptiFine was too buggy); vanilla + IEA only
  lastUsername: 'Player',
  account: null,       // last logged-in account (offline or microsoft)
};

function settingsFile(userDataDir) {
  return path.join(userDataDir, 'launcher-settings.json');
}

function loadSettings(userDataDir) {
  const file = settingsFile(userDataDir);
  try {
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    return Object.assign({}, DEFAULTS, data);
  } catch (_) {
    return Object.assign({}, DEFAULTS);
  }
}

function saveSettings(userDataDir, settings) {
  const file = settingsFile(userDataDir);
  const merged = Object.assign(loadSettings(userDataDir), settings);
  fs.writeFileSync(file, JSON.stringify(merged, null, 2));
  return merged;
}

module.exports = { loadSettings, saveSettings, DEFAULTS };
