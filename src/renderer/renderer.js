'use strict';

const $ = (id) => document.getElementById(id);

let currentAccount = null;

// ---------- tabs ----------
document.querySelectorAll('.nav-item').forEach((btn) => {
  btn.addEventListener('click', () => {
    const tab = btn.dataset.tab;
    document.querySelectorAll('.nav-item').forEach((b) => b.classList.toggle('active', b === btn));
    document.querySelectorAll('.tab').forEach((s) => s.classList.toggle('hidden', s.dataset.tab !== tab));
  });
});

// ---------- account UI ----------
function renderAccount(acc) {
  currentAccount = acc;
  if (acc) {
    $('accountName').textContent = acc.name;
    $('accountType').textContent = acc.type === 'microsoft' ? t('acc_type_microsoft') : t('acc_type_offline');
    $('avatar').textContent = (acc.name || 'P').charAt(0).toUpperCase();
  } else {
    $('accountName').textContent = t('acc_not_logged_in');
    $('accountType').textContent = '—';
    $('avatar').textContent = 'P';
  }
}

// ---------- init from settings ----------
async function init() {
  const s = await window.iea.getSettings();
  setLang(s.language || 'ja');
  $('langSelect').value = s.language || 'ja';
  $('usernameInput').value = s.lastUsername || 'Player';
  $('javaPath').value = s.javaPath || '';
  $('gameDir').value = s.gameDir || '';
  $('hypixelKey').value = s.hypixelKey || '';
  $('injectClient').checked = s.injectClient !== false;
  $('capLog').checked = s.capLog !== false;
  capLogEnabled = s.capLog !== false;
  $('ramSlider').value = s.maxRamMB || 2048;
  $('ramValue').textContent = s.maxRamMB || 2048;
  $('versionBadge').textContent = s.versionId || '1.8.9';
  renderAccount(s.account || null);
  setStatus(t('status_ready'));
}
init();

// ---------- language ----------
$('langSelect').addEventListener('change', async () => {
  const lang = $('langSelect').value;
  setLang(lang);
  renderAccount(currentAccount); // re-render non-i18n-tagged account text
  await window.iea.saveSettings({ language: lang });
});

// ---------- auth ----------
$('offlineBtn').addEventListener('click', async () => {
  const name = $('usernameInput').value.trim() || 'Player';
  const acc = await window.iea.loginOffline(name);
  renderAccount(acc);
  setStatus(`${t('status_offline_ready')}: ${acc.name}`);
});

$('msBtn').addEventListener('click', async () => {
  $('msBtn').disabled = true;
  try {
    const acc = await window.iea.loginMicrosoft();
    renderAccount(acc);
    $('msPrompt').classList.add('hidden');
    setStatus(`${t('status_signed_in')}: ${acc.name}`);
  } catch (e) {
    $('msPrompt').classList.remove('hidden');
    $('msPrompt').innerHTML = `<b style="color:var(--danger)">${t('acc_login_failed')}:</b> ${escapeHtml(e.message)}`;
  } finally {
    $('msBtn').disabled = false;
  }
});

window.iea.onAuthPrompt((p) => {
  $('msPrompt').classList.remove('hidden');
  $('msPrompt').innerHTML =
    `Open <b>${escapeHtml(p.verification_uri)}</b> · code: <code>${escapeHtml(p.user_code)}</code>`;
});

$('logoutBtn').addEventListener('click', async () => {
  await window.iea.logout();
  renderAccount(null);
  setStatus(t('status_logged_out'));
});

// ---------- settings (auto-saved so they always apply on launch) ----------
$('ramSlider').addEventListener('input', () => { $('ramValue').textContent = $('ramSlider').value; });
$('ramSlider').addEventListener('change', () => window.iea.saveSettings({ maxRamMB: parseInt($('ramSlider').value, 10) }));

$('javaPath').addEventListener('change', () => window.iea.saveSettings({ javaPath: $('javaPath').value.trim() }));
$('gameDir').addEventListener('change', () => window.iea.saveSettings({ gameDir: $('gameDir').value.trim() }));
$('hypixelKey').addEventListener('change', () => window.iea.saveSettings({ hypixelKey: $('hypixelKey').value.trim() }));
$('injectClient').addEventListener('change', () => window.iea.saveSettings({ injectClient: $('injectClient').checked }));
$('capLog').addEventListener('change', () => {
  capLogEnabled = $('capLog').checked;
  window.iea.saveSettings({ capLog: capLogEnabled });
  if (capLogEnabled) trimLog(); // applying the cap now drops any current backlog over the limit
});

$('pickJavaBtn').addEventListener('click', async () => {
  const p = await window.iea.pickJava();
  if (p) { $('javaPath').value = p; await window.iea.saveSettings({ javaPath: p }); }
});

$('pickDirBtn').addEventListener('click', async () => {
  const p = await window.iea.pickDir();
  if (p) { $('gameDir').value = p; await window.iea.saveSettings({ gameDir: p }); }
});

$('openDirBtn').addEventListener('click', () => {
  window.iea.openGameDir($('gameDir').value.trim());
});

$('saveSettingsBtn').addEventListener('click', async () => {
  await window.iea.saveSettings({
    javaPath: $('javaPath').value.trim(),
    gameDir: $('gameDir').value.trim(),
    hypixelKey: $('hypixelKey').value.trim(),
    maxRamMB: parseInt($('ramSlider').value, 10),
    language: $('langSelect').value,
    injectClient: $('injectClient').checked,
  });
  const hint = $('savedHint');
  hint.classList.remove('hidden');
  setTimeout(() => hint.classList.add('hidden'), 1500);
});

// ---------- launch ----------
function setStatus(text) { $('status').textContent = text; }
function setProgress(done, total) {
  const pct = total ? Math.round((done / total) * 100) : 0;
  $('progressBar').style.width = pct + '%';
  $('progressText').textContent = total ? `${done} / ${total} files (${pct}%)` : '';
}
// Cap the console so a long game session can't grow renderer memory without bound.
// Trim in batches (not every line) so chatty startup logging stays cheap. Toggleable:
// when off, the full log is kept (uses more memory). Default set from saved settings.
const LOG_MAX = 600, LOG_BATCH = 200;
let logLines = [];
let capLogEnabled = true;
function trimLog() {
  if (logLines.length <= LOG_MAX) return;
  logLines = logLines.slice(logLines.length - LOG_MAX);
  $('logConsole').textContent = logLines.join('\n') + '\n';
}
function appendLog(line) {
  const el = $('logConsole');
  logLines.push(line);
  if (capLogEnabled && logLines.length > LOG_MAX + LOG_BATCH) {
    logLines = logLines.slice(logLines.length - LOG_MAX);
    el.textContent = logLines.join('\n') + '\n';
  } else {
    el.textContent += line + '\n';
  }
  el.scrollTop = el.scrollHeight;
}
function escapeHtml(s) { return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

let gameRunning = false;
function setLaunchButton(running) {
  gameRunning = running;
  const btn = $('launchBtn');
  btn.textContent = running ? t('btn_stop') : t('btn_launch');
  btn.classList.toggle('stop', running);
}

window.iea.on('status', setStatus);
window.iea.on('progress', (p) => setProgress(p.done, p.total));
window.iea.on('log', appendLog);
window.iea.on('exit', () => { setLaunchButton(false); setProgress(0, 0); });

$('launchBtn').addEventListener('click', async () => {
  // While the game is running this button acts as STOP.
  if (gameRunning) {
    setStatus(t('status_stopping'));
    await window.iea.stop();
    return;
  }
  if (!currentAccount) {
    setStatus(t('status_login_first'));
    return;
  }
  setLaunchButton(true);
  setStatus(t('status_preparing'));
  const res = await window.iea.launch({ account: currentAccount });
  if (!res.ok) {
    setStatus(`${t('status_error')}: ${res.error}`);
    setLaunchButton(false);
    setProgress(0, 0);
  }
});

$('clearLogBtn').addEventListener('click', () => { logLines = []; $('logConsole').textContent = ''; });
