'use strict';

// Microsoft / Minecraft login via the `msmc` library.
//
// msmc ships with a working (community) client id, so you do NOT need to
// register your own Azure app. It opens a real Microsoft login pop-up window
// (Electron BrowserWindow) — the user just signs in normally.
//
// If you later want your own Azure app for long-term stability, pass an MSToken
// ({ client_id, redirect }) to `new Auth(...)` instead of a prompt string.
const { Auth } = require('msmc');

function dashUuid(id) {
  if (!id) return id;
  if (id.includes('-')) return id;
  return `${id.slice(0, 8)}-${id.slice(8, 12)}-${id.slice(12, 16)}-${id.slice(16, 20)}-${id.slice(20)}`;
}

function toAccount(mc) {
  const profile = mc.profile || {};
  return {
    type: 'microsoft',
    name: profile.name,
    uuid: dashUuid(profile.id),
    accessToken: mc.mcToken,
    userType: 'msa',
    refreshToken: mc.refreshTkn || null,
  };
}

/**
 * Interactive login. Opens a Microsoft sign-in pop-up and resolves to our
 * account object. `onPrompt` is unused here (kept for API compatibility) since
 * the pop-up replaces the device-code prompt.
 */
async function microsoftLogin(_onPrompt) {
  const authManager = new Auth('select_account');
  const xbox = await authManager.launch('electron', { width: 480, height: 640 });
  const mc = await xbox.getMinecraft();
  if (!mc.profile) {
    throw new Error('This Microsoft account does not own Minecraft (Java Edition).');
  }
  return toAccount(mc);
}

/**
 * Silent re-login using a stored refresh token (from a previous login).
 * Returns null if it can't refresh (caller should fall back to interactive).
 */
async function microsoftRefresh(refreshToken) {
  if (!refreshToken) return null;
  try {
    const authManager = new Auth('select_account');
    const xbox = await authManager.refresh(refreshToken);
    const mc = await xbox.getMinecraft();
    if (!mc.profile) return null;
    return toAccount(mc);
  } catch (_) {
    return null;
  }
}

module.exports = { microsoftLogin, microsoftRefresh };
