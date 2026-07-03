'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const { makePaths, ensureDirs } = require('./paths');
const { ensureVersionJson } = require('./version');
const { resolveLibraries } = require('./libraries');
const { resolveAssets } = require('./assets');
const { extractNatives } = require('./natives');
const { downloadFile, getBuffer, pool } = require('./http');
const { resolveJavaPath, detectJavaMajor } = require('./java');
const { ensureJava8 } = require('./runtime');
const { mojangOsName } = require('./rules');

/**
 * Substitute ${...} placeholders in the legacy minecraftArguments string.
 */
function fillArgs(template, vars) {
  return template.split(' ').map((tok) => {
    const m = /^\$\{(.+)\}$/.exec(tok);
    return m && m[1] in vars ? vars[m[1]] : tok;
  });
}

/**
 * Find a user-provided OptiFine jar in the optifine dir (OptiFine cannot be
 * redistributed, so the user drops their own jar here). Returns its path or null.
 */
function findOptifineJar(dir) {
  try {
    const jars = fs.readdirSync(dir)
      .filter((n) => /optifine.*\.jar$/i.test(n) || /\.jar$/i.test(n))
      .sort();
    // prefer a name that actually contains "optifine"
    const of = jars.find((n) => /optifine/i.test(n)) || jars[0];
    return of ? path.join(dir, of) : null;
  } catch (_) {
    return null;
  }
}

const OPTIFINE_FILE = 'OptiFine_1.8.9_HD_U_M5.jar'; // the last 1.8.9 release

/**
 * Download OptiFine from its official site into optifineDir (the user's own free copy;
 * we don't host it). Fetches the adloadx page to get the tokenised download URL, then the
 * jar. A manually-dropped jar is always used first (findOptifineJar). Note: this skips the
 * ad view, which OptiFine's author discourages — hence it only runs if no jar is present.
 */
async function ensureOptifine(optifineDir, emit) {
  const existing = findOptifineJar(optifineDir);
  if (existing) return existing;
  fs.mkdirSync(optifineDir, { recursive: true });
  emit('status', 'Downloading OptiFine…');
  emit('log', `Downloading ${OPTIFINE_FILE} from optifine.net…`);
  const ref = `https://optifine.net/adloadx?f=${OPTIFINE_FILE}`;
  const page = (await getBuffer(ref, { 'User-Agent': 'Mozilla/5.0' })).toString('utf8');
  const m = /href=['"](downloadx\?f=[^'"]+)['"]/.exec(page);
  if (!m) throw new Error('download link not found (optifine.net layout changed)');
  const url = 'https://optifine.net/' + m[1].replace(/&amp;/g, '&');
  const jar = await getBuffer(url, { 'User-Agent': 'Mozilla/5.0', 'Referer': ref });
  if (jar.length < 100000 || jar[0] !== 0x50 || jar[1] !== 0x4B) { // must be a real ZIP/jar
    throw new Error('response was not a jar (got ' + jar.length + ' bytes)');
  }
  const dest = path.join(optifineDir, OPTIFINE_FILE);
  fs.writeFileSync(dest, jar);
  emit('log', `OptiFine downloaded (${(jar.length / 1048576).toFixed(1)} MB): ${dest}`);
  return dest;
}

// Performance JVM flags (G1GC, tuned for a desktop game client). These mainly
// remove GC stutter and raise minimum FPS; they don't change raw rendering. Based
// on the widely-used "Aikar" G1 tuning, minus AlwaysPreTouch (which would slow
// startup). Applied before extraJvmArgs so a user override always wins.
const PERF_JVM_ARGS = [
  '-XX:+UseG1GC',
  '-XX:+UnlockExperimentalVMOptions',
  '-XX:+ParallelRefProcEnabled',
  '-XX:+DisableExplicitGC',
  '-XX:G1NewSizePercent=30',
  '-XX:G1MaxNewSizePercent=40',
  '-XX:G1HeapRegionSize=8M',
  '-XX:G1ReservePercent=20',
  '-XX:G1HeapWastePercent=5',
  '-XX:G1MixedGCCountTarget=4',
  '-XX:InitiatingHeapOccupancyPercent=15',
  '-XX:G1MixedGCLiveThresholdPercent=90',
  '-XX:G1RSetUpdatingPauseTimePercent=5',
  '-XX:SurvivorRatio=32',
  '-XX:+PerfDisableSharedMem',
  '-XX:MaxTenuringThreshold=1',
  '-XX:+UseStringDeduplication', // G1-only: dedupe identical strings to save memory
  // AlwaysPreTouch: commit + zero the whole heap at startup so the OS never page-
  // faults mid-game. Trades ~1s of startup for steadier frame times (fewer micro-
  // stutters) — Sodium-style consistency. Pairs with Xms == Xmx (set below).
  '-XX:+AlwaysPreTouch',
];

// Performance-oriented in-game defaults, written to options.txt ONLY on a fresh
// game dir (never overwrites the user's own settings). useVbo + Fast graphics +
// uncapped vsync are the real raw-FPS levers in 1.8.9.
const PERF_VERSION = 2; // bump when PERF_OPTIONS changes so existing installs re-apply
const PERF_OPTIONS = {
  renderDistance: 8,
  fancyGraphics: false,
  ao: 1,
  enableVsync: false,
  maxFps: 260,
  particles: 1,
  mipmapLevels: 0,
  // useVbo OFF: in 1.8.9 (LWJGL2) display lists are usually FASTER than VBOs,
  // especially on capable GPUs (VBO can roughly halve FPS at high framerates).
  // This is the setting 1.8.9 PvP players keep off.
  useVbo: false,
  fboEnable: true,
  // Snooper off: stops Minecraft's background telemetry thread + periodic stats
  // POST to Mojang. Purely internal — no gameplay/visual change, multiplayer-safe.
  snooperEnabled: false,
};

/**
 * Apply the performance defaults to options.txt, re-applying when PERF_VERSION
 * grows so existing installs pick up newly-added keys. We merge the perf keys into
 * any existing options.txt (preserving the user's other settings like keybinds)
 * and stamp the applied version. Between version bumps it never touches the file,
 * so the user stays free to change video settings.
 */
function writePerfOptions(gameDir, emit) {
  const stamp = path.join(gameDir, '.iea-perf-applied');
  try {
    let applied = 0;
    if (fs.existsSync(stamp)) {
      const v = parseInt(fs.readFileSync(stamp, 'utf8').trim(), 10);
      // old stamps stored Date.now() (a huge number) -> treat those as v1
      applied = (isNaN(v) || v > 1000) ? 1 : v;
    }
    if (applied >= PERF_VERSION) return; // already up to date
    const opt = path.join(gameDir, 'options.txt');
    // parse existing "key:value" lines (1.8.9 format) into a map
    const map = {};
    if (fs.existsSync(opt)) {
      for (const line of fs.readFileSync(opt, 'utf8').split(/\r?\n/)) {
        const i = line.indexOf(':');
        if (i > 0) map[line.slice(0, i)] = line.slice(i + 1);
      }
    }
    for (const [k, v] of Object.entries(PERF_OPTIONS)) map[k] = String(v);
    const out = Object.entries(map).map(([k, v]) => `${k}:${v}`).join('\n') + '\n';
    fs.writeFileSync(opt, out);
    fs.writeFileSync(stamp, String(PERF_VERSION));
    emit('log', `Applied performance defaults to options.txt (v${PERF_VERSION}).`);
  } catch (e) {
    emit('log', `Could not apply options.txt perf defaults: ${e.message}`);
  }
}

// Log4Shell (CVE-2021-44228) mitigation for 1.8.9. That version ships log4j
// 2.0-beta9, where the -Dlog4j2.formatMsgNoLookups flag does NOT work, so we use
// Mojang's official approach: override the logging config with one that strips
// message lookups (the "log4j2_17-111.xml" config for 1.7–1.11.2). A malicious
// server/chat sending ${jndi:...} can otherwise run code on the client.
const LOG4J2_XML = `<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="SysOut" target="SYSTEM_OUT">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg%n" />
        </Console>
        <RollingRandomAccessFile name="File" fileName="logs/latest.log" filePattern="logs/%d{yyyy-MM-dd}-%i.log.gz">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg%n" />
            <Policies>
                <TimeBasedTriggeringPolicy />
                <OnStartupTriggeringPolicy />
            </Policies>
        </RollingRandomAccessFile>
    </Appenders>
    <Loggers>
        <Root level="info">
            <filters>
                <MarkerFilter marker="NETWORK_PACKETS" onMatch="DENY" onMismatch="NEUTRAL" />
            </filters>
            <AppenderRef ref="SysOut" />
            <AppenderRef ref="File" />
        </Root>
    </Loggers>
</Configuration>
`;

/** Write the Log4Shell-safe logging config (once) and return its absolute path. */
function ensureLog4jConfig(paths, emit) {
  const dest = path.join(paths.root, 'log4j2-1.8.9-patched.xml');
  try {
    if (!fs.existsSync(dest)) {
      fs.writeFileSync(dest, LOG4J2_XML);
      emit('log', 'Wrote Log4Shell-safe logging config.');
    }
    return dest;
  } catch (e) {
    emit('log', `Could not write log4j config: ${e.message}`);
    return null;
  }
}

// Extra libraries needed only for the OptiFine (LaunchWrapper) launch path.
const OPTIFINE_LIBS = [
  ['launchwrapper-1.12.jar',
    'https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar'],
  ['asm-debug-all-5.0.3.jar',
    'https://repo1.maven.org/maven2/org/ow2/asm/asm-debug-all/5.0.3/asm-debug-all-5.0.3.jar'],
];

/**
 * Download + verify everything needed for the version, extract natives, then
 * spawn the JVM. `emit(type, payload)` reports progress/log/state to the UI.
 *
 * options: { userDataDir, versionId, account, javaPath, maxRamMB, minRamMB,
 *            extraJvmArgs }
 */
async function launch(options, emit) {
  const {
    userDataDir,
    versionId = '1.8.9',
    account,
    javaPath: configuredJava,
    gameDir: configuredGameDir,
    maxRamMB = 2048,
    minRamMB = 512,
    extraJvmArgs = [],
    agentPath,
    useOptifine = true,
    optimizeJvm = true,
    hypixelKey = '',
    onSpawn,
    isCancelled,
  } = options;

  const paths = makePaths(userDataDir);
  ensureDirs(paths);

  // Game working dir: custom override or the default instance folder.
  const gameDir = configuredGameDir && configuredGameDir.trim() ? configuredGameDir.trim() : paths.gameDir;
  fs.mkdirSync(gameDir, { recursive: true });
  emit('log', `Game directory: ${gameDir}`);
  // The agent reads the Hypixel API key from iea-hypixel-key.txt in this working dir.
  // Mirror the launcher's key field into it (or remove the file when the field is cleared).
  try {
    const keyFile = path.join(gameDir, 'iea-hypixel-key.txt');
    const key = (hypixelKey || '').trim();
    if (key) fs.writeFileSync(keyFile, key + '\n');
    else if (fs.existsSync(keyFile)) fs.rmSync(keyFile, { force: true });
  } catch (_) { /* non-fatal */ }
  if (optimizeJvm) writePerfOptions(gameDir, emit); // perf defaults on first run only

  emit('status', `Fetching ${versionId} manifest…`);
  const versionJson = await ensureVersionJson(versionId, paths);

  // ---- gather download list ----
  emit('status', 'Resolving libraries and assets…');
  const { downloads: libDownloads, classpath, nativeJars } = resolveLibraries(versionJson, paths);

  const clientJar = paths.versionJar(versionId);
  if (versionJson.downloads && versionJson.downloads.client) {
    libDownloads.push({
      url: versionJson.downloads.client.url,
      dest: clientJar,
      sha1: versionJson.downloads.client.sha1,
    });
  }

  // ---- OptiFine (optional): launch via LaunchWrapper if a jar is present ----
  let optifineJar = useOptifine ? findOptifineJar(paths.optifine) : null;
  if (useOptifine && !optifineJar) {
    try {
      optifineJar = await ensureOptifine(paths.optifine, emit); // auto-download the user's copy
    } catch (e) {
      emit('log', `OptiFine auto-download failed: ${e.message}. You can drop the jar in: ${paths.optifine}`);
    }
  }
  const optifineCp = [];
  if (optifineJar) {
    emit('log', `OptiFine: ${optifineJar}`);
    for (const [name, url] of OPTIFINE_LIBS) {
      const dest = path.join(paths.libraries, name);
      optifineCp.push(dest);
      if (!fs.existsSync(dest)) libDownloads.push({ url, dest });
    }
    optifineCp.push(optifineJar);
  }

  const { indexId, downloads: assetDownloads } = await resolveAssets(versionJson, paths);

  const all = libDownloads.concat(assetDownloads);

  // Startup speed: after a successful run we record the exact file set in a stamp.
  // On later launches, if the stamp matches and every file is still present, we
  // skip the whole download + SHA1-verify pass (hashing hundreds of asset files
  // each launch was the main startup cost). A missing file falls back to a full run.
  const stampFile = path.join(paths.root, `verified-${versionId}.json`);
  const sig = require('crypto').createHash('sha1')
    .update(all.map((a) => a.dest).join('\n')).digest('hex');
  let verified = false;
  try {
    const stamp = JSON.parse(fs.readFileSync(stampFile, 'utf8'));
    if (stamp.sig === sig && all.every((a) => fs.existsSync(a.dest))) verified = true;
  } catch (_) { /* no stamp yet */ }

  if (verified) {
    emit('status', 'Game files verified (cached) — skipping checks.');
    emit('log', `Skipped re-verifying ${all.length} files (cached).`);
    emit('progress', { done: all.length, total: all.length });
  } else {
    emit('status', `Downloading game files (${all.length} files)…`);
    emit('progress', { done: 0, total: all.length });
    await pool(
      all,
      (item) => downloadFile(item.url, item.dest, item.sha1).catch((e) => {
        // surface but don't abort the whole run on a single transient failure
        emit('log', `WARN download failed: ${item.url} (${e.message})`);
      }),
      8,
      (done, total) => emit('progress', { done, total })
    );
    try {
      fs.writeFileSync(stampFile, JSON.stringify({ sig, count: all.length, at: Date.now() }));
    } catch (_) { /* non-fatal */ }
  }

  // ---- natives ----
  // Re-extracting the native DLLs every launch is wasted work. A stamp lets us
  // reuse the already-extracted set; a missing stamp/dir triggers a fresh extract.
  const nativesDir = paths.nativesDir(versionId);
  const nativesStamp = path.join(nativesDir, '.iea-natives-ok');
  if (fs.existsSync(nativesStamp)) {
    emit('log', 'Natives already extracted — reusing.');
  } else {
    emit('status', 'Extracting natives…');
    fs.rmSync(nativesDir, { recursive: true, force: true });
    extractNatives(nativeJars, nativesDir);
    try { fs.writeFileSync(nativesStamp, String(Date.now())); } catch (_) { /* non-fatal */ }
  }

  // ---- java ----
  // If the user configured a Java path, respect it. Otherwise auto-download a
  // Java 8 runtime (1.8.9 requires Java 8) so the game works out of the box.
  let javaPath;
  if (configuredJava && configuredJava.trim()) {
    javaPath = resolveJavaPath(configuredJava);
  } else {
    emit('status', 'Checking Java 8 runtime…');
    javaPath = await ensureJava8(userDataDir, emit);
  }
  const major = await detectJavaMajor(javaPath);
  if (major != null && major !== 8) {
    emit('log', `WARN: Java ${major} detected. Minecraft ${versionId} needs Java 8 — it may crash.`);
  }

  // ---- build classpath ----
  // OptiFine path adds launchwrapper + asm + the OptiFine jar; LaunchWrapper's
  // class loader then loads the client and OptiFine patches it on top.
  const cp = classpath.concat([clientJar]).concat(optifineCp).join(path.delimiter);

  // ---- jvm args ----
  // With the perf flags on, pin Xms == Xmx: the heap never resizes, which removes
  // a common source of mid-game stutter (and slightly speeds warmup). Otherwise
  // respect the configured min/max.
  const xms = optimizeJvm ? maxRamMB : minRamMB;
  const jvmArgs = [
    `-Xmx${maxRamMB}M`,
    `-Xms${xms}M`,
    `-Djava.library.path=${nativesDir}`,
    `-Dminecraft.launcher.brand=iea-client`,
    `-Dminecraft.launcher.version=0.1.0`,
  ];
  // Log4Shell mitigation (always on, independent of the perf toggle).
  const log4jConfig = ensureLog4jConfig(paths, emit);
  if (log4jConfig) jvmArgs.push(`-Dlog4j.configurationFile=${log4jConfig}`);
  jvmArgs.push('-Dlog4j2.formatMsgNoLookups=true'); // harmless extra layer for newer log4j
  if (optimizeJvm) {
    jvmArgs.push(...PERF_JVM_ARGS);
    emit('log', 'Applied performance JVM flags (G1GC tuning).');
  }
  if (mojangOsName() === 'osx') jvmArgs.push('-XstartOnFirstThread');
  // Inject the IEA client (Java agent) if present — this is what turns vanilla
  // 1.8.9 into our modified client (FPS/CPS/clickGUI etc.).
  if (agentPath && fs.existsSync(agentPath)) {
    jvmArgs.push(`-javaagent:${agentPath}`);
    emit('log', `Injecting IEA client: ${agentPath}`);
  }
  jvmArgs.push(...extraJvmArgs);
  jvmArgs.push('-cp', cp);
  // OptiFine boots through LaunchWrapper; vanilla boots its own Main directly.
  jvmArgs.push(optifineJar ? 'net.minecraft.launchwrapper.Launch' : versionJson.mainClass);

  // ---- game args (1.8.9 uses the legacy minecraftArguments string) ----
  const vars = {
    auth_player_name: account.name,
    version_name: versionId,
    game_directory: gameDir,
    assets_root: paths.assets,
    assets_index_name: indexId || (versionJson.assets || '1.8'),
    auth_uuid: account.uuid,
    auth_access_token: account.accessToken,
    user_type: account.userType || 'legacy',
    user_properties: '{}',
    version_type: versionJson.type || 'release',
    auth_session: `token:${account.accessToken}:${account.uuid}`,
    game_assets: paths.assets,
  };
  const gameArgs = fillArgs(versionJson.minecraftArguments || '', vars);
  // LaunchWrapper consumes --tweakClass and hands off to OptiFine's tweaker,
  // which patches the game (custom sky, connected textures, …) then runs vanilla Main.
  if (optifineJar) gameArgs.push('--tweakClass', 'optifine.OptiFineTweaker');

  // Allow the user to cancel during the download/prepare phase (before spawn).
  if (isCancelled && isCancelled()) {
    emit('status', 'Cancelled');
    emit('exit', null);
    return null;
  }

  const finalArgs = jvmArgs.concat(gameArgs);
  emit('status', 'Launching Minecraft…');
  emit('log', `> ${javaPath} ${finalArgs.join(' ')}`);

  // ---- spawn ----
  const child = spawn(javaPath, finalArgs, { cwd: gameDir });
  if (typeof onSpawn === 'function') onSpawn(child);

  return await new Promise((resolve, reject) => {
    const onData = (buf) => buf.toString().split(/\r?\n/).forEach((l) => l && emit('log', l));
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('error', (e) => {
      emit('log', `ERROR: failed to start java — ${e.message}`);
      reject(e);
    });
    child.on('close', (code) => {
      emit('status', `Game exited (code ${code})`);
      emit('exit', code);
      resolve(code);
    });
  });
}

module.exports = { launch };
