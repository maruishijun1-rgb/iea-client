'use strict';

// Maps Node's process.platform to the OS name Mojang uses in version manifests.
function mojangOsName() {
  switch (process.platform) {
    case 'win32':
      return 'windows';
    case 'darwin':
      return 'osx';
    default:
      return 'linux';
  }
}

// The native classifier key Mojang uses for the current OS in 1.8.9-era manifests.
function nativeClassifierKey() {
  return mojangOsName(); // "windows" | "osx" | "linux"
}

/**
 * Evaluate a library's "rules" array against the current OS.
 * Returns true if the library should be used. No rules => allowed.
 */
function rulesAllow(rules) {
  if (!rules || rules.length === 0) return true;
  const osName = mojangOsName();
  let allowed = false;
  for (const rule of rules) {
    let matches = true;
    if (rule.os && rule.os.name) {
      matches = rule.os.name === osName;
    }
    if (matches) {
      allowed = rule.action === 'allow';
    }
  }
  return allowed;
}

module.exports = { mojangOsName, nativeClassifierKey, rulesAllow };
