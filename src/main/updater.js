'use strict';

const { app, dialog } = require('electron');

// Auto-update via electron-updater + GitHub Releases. The whole launcher (with the
// bundled iea-agent.jar) is replaced, so a single release ships both the UI and the
// game agent. Runs only in the packaged app — in dev there is no update feed. Every
// failure is non-fatal: a broken/offline update check must never stop the launcher.
function initAutoUpdate(getWindow) {
  if (!app.isPackaged) {
    console.log('[update] dev build — auto-update disabled');
    return;
  }

  let autoUpdater;
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch (e) {
    console.error('[update] electron-updater unavailable:', e && e.message);
    return;
  }

  autoUpdater.autoDownload = true;           // pull the update in the background
  autoUpdater.autoInstallOnAppQuit = true;   // apply on quit even if the user waits

  autoUpdater.on('error', (err) =>
    console.error('[update] error:', err ? (err.stack || err).toString() : 'unknown'));
  autoUpdater.on('checking-for-update', () => console.log('[update] checking…'));
  autoUpdater.on('update-available', (info) =>
    console.log('[update] available:', info && info.version));
  autoUpdater.on('update-not-available', () => console.log('[update] up to date'));

  autoUpdater.on('download-progress', (p) => {
    const w = getWindow && getWindow();
    if (w && !w.isDestroyed()) { try { w.setProgressBar((p.percent || 0) / 100); } catch (_) {} }
  });

  autoUpdater.on('update-downloaded', async (info) => {
    const w = getWindow && getWindow();
    if (w && !w.isDestroyed()) { try { w.setProgressBar(-1); } catch (_) {} }
    try {
      const res = await dialog.showMessageBox(w || null, {
        type: 'info',
        buttons: ['今すぐ再起動して更新', '後で'],
        defaultId: 0,
        cancelId: 1,
        noLink: true,
        title: 'IEA Client の更新',
        message: `新しいバージョン ${info && info.version ? info.version : ''} をダウンロードしました。`,
        detail: '再起動すると更新が適用されます。「後で」を選んでも、次に終了したときに自動で適用されます。',
      });
      if (res.response === 0) setImmediate(() => autoUpdater.quitAndInstall());
    } catch (e) {
      console.error('[update] prompt failed:', e && e.message);
    }
  });

  // check shortly after startup so the window can paint first
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch((e) => console.error('[update] check failed:', e && e.message));
  }, 3000);
}

module.exports = { initAutoUpdate };
