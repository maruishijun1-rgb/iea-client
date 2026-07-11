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

function toAccount(mc, refreshToken) {
  const profile = mc.profile || {};
  return {
    type: 'microsoft',
    name: profile.name,
    uuid: dashUuid(profile.id),
    accessToken: mc.mcToken,
    userType: 'msa',
    // msmc v5: the refresh token comes from xbox.save() (mc.refreshTkn is only set on the
    // Auth-refresh path, not after an interactive Xbox login) — without it we can't silently
    // re-auth, so the ~24h access token would expire and the game boots you at a server.
    refreshToken: refreshToken || mc.refreshTkn || null,
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
  let refresh = null;
  try { refresh = xbox.save(); } catch (_) { /* no refresh token available */ }
  return toAccount(mc, refresh);
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
    // capture the (possibly rotated) refresh token so the NEXT launch can refresh too;
    // fall back to the token we were given if save() yields nothing.
    let refresh = null;
    try { refresh = xbox.save(); } catch (_) { /* ignore */ }
    return toAccount(mc, refresh || refreshToken);
  } catch (_) {
    return null;
  }
}

module.exports = { microsoftLogin, microsoftRefresh };
