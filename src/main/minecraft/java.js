'use strict';

const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

/**
 * Resolve the java executable to use.
 *  - If the user configured a path, accept either the binary itself or a JDK/JRE
 *    home directory (we'll append bin/java[.exe]).
 *  - Otherwise fall back to "java" on PATH.
 *
 * NOTE: Minecraft 1.8.9 requires a Java 8 runtime. Newer JDKs (9+) will fail to
 * launch the legacy client. The UI surfaces this; here we just locate a binary.
 */
function resolveJavaPath(configuredPath) {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';
  if (configuredPath && configuredPath.trim()) {
    let p = configuredPath.trim();
    try {
      const stat = fs.statSync(p);
      if (stat.isDirectory()) {
        const candidate = path.join(p, 'bin', exe);
        if (fs.existsSync(candidate)) return candidate;
      } else {
        return p; // a file was given directly
      }
    } catch (_) {
      // configured path doesn't exist; fall through to PATH default
    }
  }
  return exe.replace('.exe', ''); // "java" — resolved via PATH at spawn time
}

/**
 * Run `java -version` and return the parsed major version number (e.g. 8, 17),
 * or null if it can't be determined. Used to warn when it isn't Java 8.
 */
function detectJavaMajor(javaPath) {
  return new Promise((resolve) => {
    execFile(javaPath, ['-version'], (err, _stdout, stderr) => {
      if (err) return resolve(null);
      // `java -version` writes to stderr, e.g. version "1.8.0_402" or "17.0.1"
      const m = /version "(\d+)(?:\.(\d+))?/.exec(stderr || '');
      if (!m) return resolve(null);
      const major = m[1] === '1' ? parseInt(m[2], 10) : parseInt(m[1], 10);
      resolve(Number.isNaN(major) ? null : major);
    });
  });
}

module.exports = { resolveJavaPath, detectJavaMajor };
