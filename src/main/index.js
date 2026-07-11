'use strict';

const { app, BrowserWindow, ipcMain, dialog, shell, screen } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { makePaths } = require('./minecraft/paths');

const { launch } = require('./minecraft/launcher');
const { offlineAccount } = require('./minecraft/auth/offline');
const { microsoftLogin, microsoftRefresh } = require('./minecraft/auth/microsoft');
const {
  loadSettings, saveSettings,
  listAccounts, upsertAccount, selectAccount, removeAccount, migrateAccounts,
} = require('./settings');
const { initAutoUpdate } = require('./updater');
const skins = require('./skins');
const { getNews } = require('./news');
const discord = require('./discord');
const { detectPack, convertJavaPack, convertBedrockPack } = require('./resourcepack/convert');

// --- memory: the launcher UI is static, so the Chromium GPU process buys us nothing.
// Disabling hardware acceleration drops that whole process (~tens of MB + GPU buffers).
// The js-flags cap is a ceiling so a long, chatty game session can't let V8 grow unbounded.
app.disableHardwareAcceleration();
app.commandLine.appendSwitch('js-flags', '--max-old-space-size=256');

let mainWindow = null;

function userData() {
  return app.getPath('userData');
}

// Path to the built client agent. Dev: <root>/client/build/iea-agent.jar.
// Packaged: bundled next to resources.
function agentJarPath() {
  const dev = path.join(__dirname, '..', '..', 'client', 'build', 'iea-agent.jar');
  if (fs.existsSync(dev)) return dev;
  const packaged = path.join(process.resourcesPath || '', 'iea-agent.jar');
  return packaged;
}

function createWindow() {
  // Default to ~80% of the screen's work area (regardless of monitor size), centered.
  const { width: sw, height: sh } = screen.getPrimaryDisplay().workAreaSize;
  mainWindow = new BrowserWindow({
    width: Math.max(900, Math.round(sw * 0.8)),
    height: Math.max(600, Math.round(sh * 0.8)),
    minWidth: 900,
    minHeight: 600,
    resizable: true,
    maximizable: true,
    fullscreenable: true,
    frame: false, // custom in-app title bar (React UI)
    backgroundColor: '#0e0f14',
    title: 'IEA Client',
    icon: path.join(__dirname, '..', 'renderer', 'assets', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.center();
  // Built React renderer (Vite -> renderer-dist), loaded via file://.
  mainWindow.loadFile(path.join(__dirname, '..', '..', 'renderer-dist', 'index.html'));
}

// Frameless window controls (custom title bar buttons -> real window actions).
ipcMain.on('win:minimize', () => { if (mainWindow) mainWindow.minimize(); });
ipcMain.on('win:maximize', () => {
  if (!mainWindow) return;
  if (mainWindow.isMaximized()) mainWindow.unmaximize(); else mainWindow.maximize();
});
ipcMain.on('win:close', () => { if (mainWindow) mainWindow.close(); });

app.whenReady().then(() => {
  migrateAccounts(userData()); // seed the account list from a legacy single account
  createWindow();
  initAutoUpdate(() => mainWindow); // GitHub Releases auto-update (packaged builds only)
  const s = loadSettings(userData());
  if (s.discordRpc !== false) discord.start(); // Discord Rich Presence (idle state)
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => { try { discord.stop(); } catch (_) {} });

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

function send(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send(channel, payload);
}

// ---------------- IPC ----------------

ipcMain.handle('settings:get', () => loadSettings(userData()));

ipcMain.handle('settings:save', (_e, patch) => saveSettings(userData(), patch));

ipcMain.handle('dialog:pickJava', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'Select Java 8 executable or home folder',
    properties: ['openFile', 'openDirectory'],
  });
  if (result.canceled || result.filePaths.length === 0) return null;
  return result.filePaths[0];
});

ipcMain.handle('dialog:pickDir', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'Select game directory',
    properties: ['openDirectory', 'createDirectory'],
  });
  if (result.canceled || result.filePaths.length === 0) return null;
  return result.filePaths[0];
});

ipcMain.handle('dialog:openGameDir', (_e, dir) => {
  const target = dir && dir.trim() ? dir.trim() : makePaths(userData()).gameDir;
  fs.mkdirSync(target, { recursive: true });
  shell.openPath(target);
  return target;
});

ipcMain.handle('auth:offline', (_e, username) => {
  const account = offlineAccount(username);
  upsertAccount(userData(), account); // add to the switcher + make active
  return account;
});

ipcMain.handle('auth:microsoft', async () => {
  const account = await microsoftLogin((prompt) => send('auth:prompt', prompt));
  upsertAccount(userData(), account);
  return account;
});

ipcMain.handle('auth:logout', () => {
  saveSettings(userData(), { account: null });
  return true;
});

// ---- account switcher ----
ipcMain.handle('accounts:list', () => listAccounts(userData()));
ipcMain.handle('accounts:select', (_e, id) => selectAccount(userData(), id));
ipcMain.handle('accounts:remove', (_e, id) => removeAccount(userData(), id));

// ---- player skin images (fetched here to satisfy the renderer CSP) ----
ipcMain.handle('skin:face', (_e, uuid) => skins.faceUrl(uuid));
ipcMain.handle('skin:body', (_e, uuid, model) => skins.bodyUrl(uuid, model));
ipcMain.handle('skin:model', (_e, uuid) => skins.modelOf(uuid)); // 'slim' | 'classic'

// ---- what's-new (GitHub releases) + app version ----
ipcMain.handle('news:get', () => getNews());
ipcMain.handle('app:version', () => app.getVersion());

// ---- saved servers (Servers tab) + live ping ----
const serverping = require('./serverping');
ipcMain.handle('servers:list', () => loadSettings(userData()).servers || []);
ipcMain.handle('servers:add', (_e, name, address) => {
  const s = loadSettings(userData());
  const servers = (s.servers || []).slice();
  servers.push({ id: Date.now().toString(), name: String(name || address), address: String(address) });
  saveSettings(userData(), { servers });
  return servers;
});
ipcMain.handle('servers:remove', (_e, id) => {
  const s = loadSettings(userData());
  const servers = (s.servers || []).filter((sv) => sv.id !== id);
  saveSettings(userData(), { servers });
  return servers;
});
ipcMain.handle('servers:ping', (_e, address) => serverping.ping(address));

// ---- resource packs (import + convert modern Java packs to 1.8.9) ----
const options = require('./options');
function gameDirPath() {
  const s = loadSettings(userData());
  return s.gameDir && s.gameDir.trim() ? s.gameDir.trim() : makePaths(userData()).gameDir;
}
function resourcePacksDir() {
  const dir = path.join(gameDirPath(), 'resourcepacks');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

ipcMain.handle('packs:list', () => {
  const dir = resourcePacksDir();
  try {
    const names = fs.readdirSync(dir, { withFileTypes: true })
      .filter((e) => e.isDirectory() || /\.zip$/i.test(e.name));
    const all = names.map((e) => e.name);
    const active = new Set(options.activeNames(gameDirPath(), all)); // from options.txt
    return names.map((e) => ({ name: e.name, folder: e.isDirectory(), active: active.has(e.name) }));
  } catch (_) { return []; }
});

// Activate/deactivate a pack by editing options.txt's resourcePacks list.
ipcMain.handle('packs:toggle', (_e, name, on) => {
  try {
    const dir = resourcePacksDir();
    const all = fs.readdirSync(dir, { withFileTypes: true })
      .filter((e) => e.isDirectory() || /\.zip$/i.test(e.name)).map((e) => e.name);
    let active = options.activeNames(gameDirPath(), all).filter((n) => all.includes(n));
    active = active.filter((n) => n !== name);
    if (on) active.push(name); // last entry = highest priority (top of the game's list)
    options.setActive(gameDirPath(), active, all);
    return active;
  } catch (e) { return null; }
});

ipcMain.handle('packs:open', () => { const d = resourcePacksDir(); shell.openPath(d); return d; });

// A pack's pack.png icon (folder or zip) as a data URL, for the Resource packs list.
ipcMain.handle('packs:icon', (_e, name) => {
  try {
    const target = path.join(resourcePacksDir(), path.basename(name));
    let buf = null;
    if (fs.existsSync(target) && fs.statSync(target).isDirectory()) {
      const p = path.join(target, 'pack.png');
      if (fs.existsSync(p)) buf = fs.readFileSync(p);
    } else if (/\.zip$/i.test(target) && fs.existsSync(target)) {
      const AdmZip = require('adm-zip');
      const e = new AdmZip(target).getEntry('pack.png');
      if (e) buf = e.getData();
    }
    if (!buf || buf.length < 8) return null;
    return 'data:image/png;base64,' + buf.toString('base64');
  } catch (_) { return null; }
});

ipcMain.handle('packs:remove', (_e, name) => {
  const dir = resourcePacksDir();
  const target = path.join(dir, path.basename(name)); // guard against traversal
  if (target.startsWith(dir)) fs.rmSync(target, { recursive: true, force: true });
  return true;
});

ipcMain.handle('packs:import', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'リソースパックを選択',
    properties: ['openFile'],
    filters: [{ name: 'Resource pack', extensions: ['zip', 'mcpack', 'mcaddon'] }],
  });
  if (result.canceled || !result.filePaths.length) return { ok: false, canceled: true };
  const src = result.filePaths[0];
  try {
    const info = detectPack(src);
    const dir = resourcePacksDir();
    if (info.kind === 'bedrock') {
      const packs = convertBedrockPack(src, dir, (t, p) => send('game:' + t, p));
      const files = packs.reduce((a, r) => a + r.files, 0);
      const fixed = packs.reduce((a, r) => a + r.fixed + r.anim, 0);
      return { ok: true, kind: 'bedrock', name: packs.map((r) => r.name).join(', '), files, fixed };
    }
    if (info.kind === 'unknown') return { ok: false, kind: 'unknown', name: info.name };
    if (info.kind === 'java-native') {
      const destName = path.basename(src);
      fs.copyFileSync(src, path.join(dir, destName)); // 1.8.9 reads it directly
      return { ok: true, kind: 'java-native', name: destName };
    }
    const res = convertJavaPack(src, dir, (t, p) => send('game:' + t, p));
    return { ok: true, kind: 'java-new', name: res.name, files: res.files, packFormat: res.packFormat };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

let launching = false;
let gameChild = null;
let cancelRequested = false;

ipcMain.handle('game:stop', () => {
  cancelRequested = true;
  if (!gameChild) return { ok: true, killed: false };
  try {
    if (process.platform === 'win32') {
      // /t kills the whole tree, /f forces it — terminates java.exe reliably.
      spawn('taskkill', ['/pid', String(gameChild.pid), '/t', '/f']);
    } else {
      gameChild.kill('SIGTERM');
    }
    return { ok: true, killed: true };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

ipcMain.handle('game:launch', async (_e, opts) => {
  if (launching) return { ok: false, error: 'A launch is already in progress.' };
  launching = true;
  cancelRequested = false;
  const settings = loadSettings(userData());
  let account = opts.account || settings.account;
  if (!account) {
    launching = false;
    return { ok: false, error: 'No account. Log in (offline or Microsoft) first.' };
  }
  // Microsoft access tokens expire (~24h); refresh silently before launching.
  if (account.type === 'microsoft' && account.refreshToken) {
    const refreshed = await microsoftRefresh(account.refreshToken);
    if (refreshed) {
      account = refreshed;
      upsertAccount(userData(), account); // keep the switcher entry fresh too
    }
  }
  discord.setPlaying(account.name); // Rich Presence: in game
  try {
    const code = await launch(
      {
        userDataDir: userData(),
        versionId: settings.versionId || '1.8.9',
        account,
        javaPath: settings.javaPath,
        gameDir: settings.gameDir,
        maxRamMB: settings.maxRamMB,
        minRamMB: settings.minRamMB,
        agentPath: settings.injectClient !== false ? agentJarPath() : null,
        useOptifine: false, // hard-disabled: IEA + OptiFine combo was too buggy (vanilla + IEA only)
        optimizeJvm: settings.optimizeJvm !== false, // perf flags + first-run options.txt
        hypixelKey: settings.hypixelKey || '', // written to iea-hypixel-key.txt for LevelHead
        server: opts.server || null, // direct-connect target (Servers tab)
        onSpawn: (child) => { gameChild = child; },
        isCancelled: () => cancelRequested,
      },
      (type, payload) => send('game:' + type, payload)
    );
    return { ok: true, code };
  } catch (err) {
    send('game:log', 'FATAL: ' + err.message);
    return { ok: false, error: err.message };
  } finally {
    launching = false;
    gameChild = null;
    discord.setIdle(); // back to the idle presence when the game stops
  }
});

// Toggle Discord Rich Presence live from the settings screen.
ipcMain.handle('discord:set', (_e, on) => {
  saveSettings(userData(), { discordRpc: !!on });
  if (on) discord.start(); else discord.stop();
  return true;
});
