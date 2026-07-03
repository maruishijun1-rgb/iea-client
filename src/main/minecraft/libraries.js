'use strict';

const path = require('path');
const { rulesAllow, mojangOsName } = require('./rules');

function archBits() {
  return process.arch === 'ia32' || process.arch === 'arm' ? '32' : '64';
}

// Libraries we never need at runtime — skipped from download, classpath, and
// native extraction to shrink the install and speed up class loading. Twitch
// broadcasting was shut down by Twitch years ago; the game only touches it if you
// open the (dead) broadcast UI, so dropping it is safe. Matched against lib.name.
const SKIP_LIBS = ['tv.twitch:'];

function isSkipped(name) {
  return !!name && SKIP_LIBS.some((s) => name.startsWith(s));
}

/**
 * Walk a version JSON's libraries and decide what to download for this OS.
 *
 * Returns:
 *   downloads:   [{ url, dest, sha1 }]            files to fetch
 *   classpath:   [absolutePath, ...]              regular jars for the -cp
 *   nativeJars:  [{ jar, exclude:[...] }]         native jars to extract
 */
function resolveLibraries(versionJson, paths) {
  const osName = mojangOsName();
  const downloads = [];
  const classpath = [];
  const nativeJars = [];

  for (const lib of versionJson.libraries || []) {
    if (!rulesAllow(lib.rules)) continue;
    if (isSkipped(lib.name)) continue; // drop unused libs (e.g. Twitch)
    const dl = lib.downloads || {};

    // Regular artifact -> classpath
    if (dl.artifact && dl.artifact.path) {
      const dest = path.join(paths.libraries, dl.artifact.path);
      downloads.push({ url: dl.artifact.url, dest, sha1: dl.artifact.sha1 });
      classpath.push(dest);
    }

    // Native artifact -> extracted into the natives dir
    if (lib.natives && lib.natives[osName]) {
      const classifier = lib.natives[osName].replace('${arch}', archBits());
      const native = dl.classifiers && dl.classifiers[classifier];
      if (native && native.path) {
        const dest = path.join(paths.libraries, native.path);
        downloads.push({ url: native.url, dest, sha1: native.sha1 });
        const exclude = (lib.extract && lib.extract.exclude) || ['META-INF/'];
        nativeJars.push({ jar: dest, exclude });
      }
    }
  }

  return { downloads, classpath, nativeJars };
}

module.exports = { resolveLibraries };
