'use strict';

const crypto = require('crypto');

/**
 * Build an offline (no Microsoft login) account. The UUID is derived the same
 * way vanilla derives offline player UUIDs: a name-based UUID (v3-ish) of
 * "OfflinePlayer:<name>". Good enough for singleplayer / LAN / offline servers.
 *
 * Online (premium) servers will reject this token — use Microsoft auth for those.
 */
function offlineAccount(username) {
  const name = (username || 'Player').trim() || 'Player';
  const md5 = crypto.createHash('md5').update('OfflinePlayer:' + name).digest();
  // Set version (3) and variant bits like java.util.UUID.nameUUIDFromBytes
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  const hex = md5.toString('hex');
  const uuid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  return {
    type: 'offline',
    name,
    uuid,
    accessToken: '0',
    userType: 'legacy',
  };
}

module.exports = { offlineAccount };
