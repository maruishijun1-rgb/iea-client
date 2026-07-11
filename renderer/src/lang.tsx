import React, { createContext, useContext } from 'react';

// Simple i18n: English strings are the keys. When language is 'ja' we look them up
// in JA (falling back to the English text if a translation is missing), so wrapping
// a string in t("...") is always safe.
const JA: Record<string, string> = {
  // sidebar / nav
  'GAME PLAY': 'プレイ',
  'CLIENT NEWS': 'お知らせ',
  "What's New": 'お知らせ',
  'Updates & release notes': '更新履歴・リリースノート',
  'Loading…': '読み込み中…',
  'Could not load release notes.': 'リリース情報を取得できませんでした。',
  'Latest': '最新',
  'Current': '使用中',
  'SERVERS': 'サーバー',
  'PROFILES': 'アカウント',
  'RESOURCES': 'リソースパック',
  'SETTINGS': '設定',
  'CONSOLE': 'ログ',
  'CLIENT READY': '準備完了',
  'LAUNCHING': '起動中',
  'Microsoft Account': 'Microsoft アカウント',
  'Offline Mode': 'オフライン',
  'Premium': 'プレミアム',
  'Offline': 'オフライン',
  'Not logged in': '未ログイン',

  // play
  'READY TO PLAY': '準備完了',
  'Minecraft version: 1.8.9': 'Minecraft バージョン: 1.8.9',
  'Ready to launch': '起動できます',
  'Vanilla 1.8.9 with the IEA client agent': 'バニラ 1.8.9 + IEA クライアント',
  'LAUNCH MINECRAFT': 'Minecraft を起動',
  'RELAUNCH GAME CLIENT': 'もう一度起動',
  'LIVE HANDSHAKE LOGS': 'ログを見る',
  'Active Identity': 'ログイン中',
  'Player Profile': 'プロフィール',

  // accounts
  'PREMIUM AUTH': 'Microsoft ログイン',
  'Securely log in via Mojang / Microsoft OAuth': 'Mojang / Microsoft で安全にログイン',
  'SIGN IN WITH MICROSOFT': 'Microsoft でサインイン',
  'OFFLINE PROFILE': 'オフラインアカウント',
  'Play on local or cracked Minecraft servers': 'ローカル / オフラインサーバーで遊ぶ',
  'USERNAME': 'ユーザー名',
  'ADD OFFLINE PROFILE': 'オフラインで追加',
  'STORED PROFILES': '保存済みアカウント',
  'Quickly swap active profiles for Minecraft session': 'クリックで使用するアカウントを切り替え',

  // packs
  'RESOURCE PACKS': 'リソースパック',
  'Installed packs': '導入済みパック',
  'Import pack…': 'パックを取り込む…',
  'Open folder': 'フォルダを開く',

  // servers
  'Direct Connection': 'ダイレクト接続',
  'Launch straight into a server without saving it': '保存せずにサーバーへ直接接続して起動',
  'CONNECT & LAUNCH GAME': '接続して起動',
  'Add Saved Server': 'サーバーを追加',
  'Save your favorite servers for quick access': 'お気に入りのサーバーを保存',
  'Server Name': 'サーバー名',
  'IP Address / Host': 'IPアドレス / ホスト',
  'REGISTER SERVER': '登録',
  'Saved Servers': '保存済みサーバー',
  'Live ping · one-click connect': 'リアルタイムping・ワンクリック接続',
  'No saved servers yet.': 'まだサーバーがありません。',
  'Add one on the left, or use Direct Connect.': '左から追加するか、ダイレクト接続を使ってください。',
  'Server Address (IP:Port)': 'サーバーアドレス (IP:ポート)',
  'Launch': '起動',
  'Pinging…': '確認中…',

  // settings
  'Game Preferences': 'ゲーム設定',
  'Language / 言語': '言語 / Language',
  'Memory Allocation (RAM)': 'メモリ割り当て(RAM)',
  'Java Executable Path': 'Java 実行ファイルのパス',
  'Auto Detect': '自動検出',
  'Browse': '参照',
  'Game Directory (.minecraft)': 'ゲームフォルダ (.minecraft)',
  'Client Features': 'クライアント機能',
  'Enable IEA Client patches': 'IEA クライアントを有効化',
  'FPS optimization, PvP HUD & custom crosshairs.': 'FPS最適化・PvP HUD・カスタムクロスヘア。',
  'Discord Rich Presence': 'Discord リッチプレゼンス',
  'Show active status, current server & stats on Discord.': 'Discord に状態・接続中のサーバー・統計を表示。',
  'Limit Log File Size': 'ログ表示を制限',
  'Auto-clear startup logs older than 7 days to save disk.': '古いログを自動的に減らしてメモリ/容量を節約。',
  'Hypixel Integration': 'Hypixel 連携',
  'Hypixel API Key': 'Hypixel API キー',
  'Low RAM (May lag)': '少なめ(カクつく可能性)',
  'Recommended for 1.8.9': '1.8.9 に推奨',
  'High RAM (May cause GC lag)': '多すぎ(GCラグの可能性)',

  // accounts
  'Premium Auth': 'Microsoft ログイン',
  'Securely log in via Mojang / Microsoft OAuth': 'Mojang / Microsoft で安全にログイン',
  'Offline Profile': 'オフラインアカウント',
  'Play on local or cracked Minecraft servers': 'ローカル / オフラインサーバーで遊ぶ',
  'Username': 'ユーザー名',
  'Stored Profiles': '保存済みアカウント',
  'No accounts saved.': '保存済みアカウントはありません。',
  'SIGN IN WITH MICROSOFT': 'Microsoft でサインイン',
  'ADD OFFLINE PROFILE': 'オフラインで追加',
  'Quickly swap active profiles for Minecraft session': 'クリックで使用するアカウントを切り替え',
  'Add an offline name or sign in with Microsoft above.': '上でオフライン名を追加するか Microsoft でサインイン。',
  'Added': '追加日',
  'Username cannot be empty.': 'ユーザー名を入力してください。',
  'Username must be 3-16 characters and Alphanumeric or Underline.': 'ユーザー名は3〜16文字の英数字・アンダースコアで入力してください。',
  'An account with this username already exists.': 'このユーザー名のアカウントは既に存在します。',

  // packs
  'Available Packs': '利用可能なパック',
  'Active Packs': '有効なパック',
  'No active packs.': '有効なパックはありません。',
  'No packs available.': 'パックがありません。',
  'Loaded into Minecraft': 'Minecraft に読込済み',
  'Not loaded into game': '未読込',
  'Click right arrow on available packs.': '左の一覧から右矢印で有効化。',
  'Import pack zip file above.': '上のボタンからパックを取り込み。',
  'Drag & Drop .zip to Import Pack': '.zip をドラッグ&ドロップ、またはクリックで取り込み',
  'Drop file to Import Resource Pack': 'ドロップして取り込み',
  'or click to browse': 'クリックしてファイルを選択',
  'packs': '個',
  'active': '有効',

  // logs
  'Client Console': 'ゲームログ',
  'Console is empty.': 'ログはありません。',
  'Start game to stream initialization logs here.': 'ゲームを起動すると初期化ログがここに流れます。',
  'Auto Scroll': '自動スクロール',
  'Clear': 'クリア',
  'Copy': 'コピー',
  'Enter PvP Name': 'PvP名を入力',
};

export const makeT = (lang: 'en' | 'ja') => (s: string) => (lang === 'ja' ? (JA[s] ?? s) : s);

const Ctx = createContext<(s: string) => string>((s) => s);

export function LangProvider({ lang, children }: { lang: 'en' | 'ja'; children: React.ReactNode }) {
  return <Ctx.Provider value={makeT(lang)}>{children}</Ctx.Provider>;
}

export const useT = () => useContext(Ctx);
