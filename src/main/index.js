'use strict';

const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { makePaths } = require('./minecraft/paths');

const { launch } = require('./minecraft/launcher');
const { offlineAccount } = require('./minecraft/auth/offline');
const { microsoftLogin, microsoftRefresh } = require('./minecraft/auth/microsoft');
const { loadSettings, saveSettings } = require('./settings');
const { initAutoUpdate } = require('./updater');

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
  mainWindow = new BrowserWindow({
    width: 980,
    height: 640,
    resizable: false,
    maximizable: false,
    fullscreenable: false,
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
  mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
}

app.whenReady().then(() => {
  createWindow();
  initAutoUpdate(() => mainWindow); // GitHub Releases auto-update (packaged builds only)
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

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
  saveSettings(userData(), { account, lastUsername: account.name });
  return account;
});

ipcMain.handle('auth:microsoft', async () => {
  const account = await microsoftLogin((prompt) => send('auth:prompt', prompt));
  saveSettings(userData(), { account, lastUsername: account.name });
  return account;
});

ipcMain.handle('auth:logout', () => {
  saveSettings(userData(), { account: null });
  return true;
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
      saveSettings(userData(), { account });
    }
  }
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
  }
});
