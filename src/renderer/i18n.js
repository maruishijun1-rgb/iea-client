'use strict';

// Simple i18n dictionary. Add a language by adding a key block here and an
// <option> in index.html's #langSelect.
const I18N = {
  ja: {
    nav_play: 'プレイ',
    nav_account: 'アカウント',
    nav_settings: '設定',
    nav_logs: 'ログ',
    btn_launch: '起動',
    btn_stop: '停止',
    status_ready: '準備完了',
    status_stopping: '停止しています…',
    status_login_first: '先にログインしてください(アカウントタブ)',
    status_preparing: '準備中…',
    status_logged_out: 'ログアウトしました',
    status_offline_ready: 'オフラインアカウント準備完了',
    status_signed_in: 'サインインしました',
    status_error: 'エラー',

    acc_title: 'アカウント',
    acc_offline_label: 'オフライン用ユーザー名',
    acc_use_offline: 'オフラインで使う',
    acc_offline_note: 'オフラインモードはシングル/LAN/オフラインサーバー専用です。',
    acc_ms_label: 'Microsoft アカウント',
    acc_ms_signin: 'Microsoft でサインイン',
    acc_logout: 'ログアウト',
    acc_ms_note: 'Microsoft のサインイン画面がポップアップします。事前設定は不要です。',
    acc_not_logged_in: '未ログイン',
    acc_type_offline: 'オフライン',
    acc_type_microsoft: 'Microsoft',
    acc_login_failed: 'ログイン失敗',

    set_title: '設定',
    set_lang_label: '言語',
    set_inject_label: 'IEA クライアント(MOD / clickGUI)を有効化',
    set_inject_note: 'IEAエージェントを注入します。ゲーム内メニューは右Shiftで開きます。',
    set_caplog_label: 'ログ表示を制限する(メモリ節約)',
    set_caplog_note: '最新の行だけ残します。オフにすると全ログを保持します(長時間プレイでメモリ増加)。',
    set_hypixel_label: 'Hypixel APIキー(LevelHead用)',
    set_hypixel_ph: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
    set_hypixel_note: 'developer.hypixel.net で作成。起動時に iea-hypixel-key.txt へ保存します。',
    set_java_label: 'Java(1.8.9 には Java 8 が必要)',
    set_java_ph: '空欄なら Java 8 を自動ダウンロード',
    set_browse: '参照…',
    set_open: '開く',
    set_dir_label: 'ゲームディレクトリ',
    set_dir_ph: '空欄なら既定の場所を使用',
    set_dir_note: 'セーブ・設定・スクリーンショットの保存先です。',
    set_ram_label: 'メモリ(最大RAM)',
    set_save: '設定を保存',
    set_saved: '保存しました ✓',

    logs_title: 'ゲームログ',
    logs_clear: 'クリア',
  },
  en: {
    nav_play: 'Play',
    nav_account: 'Account',
    nav_settings: 'Settings',
    nav_logs: 'Logs',
    btn_launch: 'LAUNCH',
    btn_stop: 'STOP',
    status_ready: 'Ready',
    status_stopping: 'Stopping…',
    status_login_first: 'Log in first (Account tab).',
    status_preparing: 'Preparing…',
    status_logged_out: 'Logged out',
    status_offline_ready: 'Offline account ready',
    status_signed_in: 'Signed in',
    status_error: 'Error',

    acc_title: 'Account',
    acc_offline_label: 'Offline username',
    acc_use_offline: 'Use Offline',
    acc_offline_note: 'Offline mode works for singleplayer / LAN / offline servers only.',
    acc_ms_label: 'Microsoft account',
    acc_ms_signin: 'Sign in with Microsoft',
    acc_logout: 'Log out',
    acc_ms_note: 'A Microsoft sign-in window will pop up — no setup needed.',
    acc_not_logged_in: 'Not logged in',
    acc_type_offline: 'Offline',
    acc_type_microsoft: 'Microsoft',
    acc_login_failed: 'Login failed',

    set_title: 'Settings',
    set_lang_label: 'Language',
    set_inject_label: 'Enable IEA client (mods / clickGUI)',
    set_inject_note: 'Injects the IEA agent. Open the in-game menu with Right Shift.',
    set_caplog_label: 'Limit the log console (saves memory)',
    set_caplog_note: 'Keeps only the most recent lines. Turn off to keep the full log (uses more memory on long sessions).',
    set_hypixel_label: 'Hypixel API key (for LevelHead)',
    set_hypixel_ph: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
    set_hypixel_note: 'Create one at developer.hypixel.net. Saved to iea-hypixel-key.txt on launch.',
    set_java_label: 'Java (Java 8 required for 1.8.9)',
    set_java_ph: 'Leave empty to auto-download Java 8',
    set_browse: 'Browse…',
    set_open: 'Open',
    set_dir_label: 'Game directory',
    set_dir_ph: 'Leave empty for the default location',
    set_dir_note: 'Where saves, options and screenshots are stored.',
    set_ram_label: 'Memory (Max RAM)',
    set_save: 'Save settings',
    set_saved: 'Saved ✓',

    logs_title: 'Game logs',
    logs_clear: 'Clear',
  },
};

let currentLang = 'ja';

function setLang(lang) {
  currentLang = I18N[lang] ? lang : 'ja';
  document.documentElement.lang = currentLang;
  applyI18n();
}

function t(key) {
  const dict = I18N[currentLang] || I18N.ja;
  return dict[key] != null ? dict[key] : (I18N.en[key] != null ? I18N.en[key] : key);
}

function applyI18n() {
  document.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = t(el.dataset.i18n);
  });
  document.querySelectorAll('[data-i18n-ph]').forEach((el) => {
    el.placeholder = t(el.dataset.i18nPh);
  });
}
