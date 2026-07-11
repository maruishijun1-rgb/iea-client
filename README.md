# IEA Client

Minecraft **1.8.9** 用 PvP ランチャー(Electron 製、Windows / macOS 両対応)。

> 現在のスコープ: **ランチャーのみ**。改造済みクライアント(独自MOD/インジェクション)は未実装で、
> いまは公式の純正 1.8.9 をダウンロードして起動します。

## できること

- 公式 Mojang サーバーから 1.8.9 の client.jar / ライブラリ / アセット / ネイティブを取得・SHA1検証
- ネイティブの展開とクラスパス・JVM引数の自動組み立て(macOS では `-XstartOnFirstThread` も付与)
- **オフラインログイン**(ユーザー名のみ。シングル/LAN/オフラインサーバー用)
- **Microsoft ログイン**(`msmc` 利用。ポップアップでサインインするだけ、**Azure 設定不要**。プレミアム/オンラインサーバー用)
- **Java 8 ランタイムの自動ダウンロード**(Temurin JRE 8 / Adoptium。Java欄が空欄のとき初回起動時に取得)
- 設定タブ: **言語切替(日本語 / English)**、**ゲームディレクトリ変更**(参照/開く)、Java パス指定、最大メモリ設定
- ゲームログ表示

## 必要なもの

- **Node.js 18 以上**(開発・ビルド時)— 未インストールなら下記参照
- **Java 8 ランタイム** — 手動インストールは**不要**。Settings の Java 欄を空欄にしておくと、
  初回起動時に **Temurin JRE 8 を自動ダウンロード**(`<userData>/runtime/jre8`)して使います。
  自前の Java 8 を使いたい場合のみ Settings でパスを指定してください
  (1.8.9 は Java 9+ では起動しません)。

### Node.js のインストール(Windows)

```powershell
winget install OpenJS.NodeJS.LTS
# もしくは https://nodejs.org からインストーラ
```

## 開発・起動

```bash
npm install
npm start
```

1. **Account** タブでオフライン名を入力 →「Use Offline」、または Microsoft ログイン
2. **Settings** で Java 8 のパスを指定(空欄なら PATH の `java`)
3. **Play** タブの **LAUNCH** を押す

ゲームファイルとログは Electron の userData 配下(`game/` フォルダ)に保存されます。

## ビルド(配布物の作成)

```bash
npm run dist:win   # Windows (.exe / NSIS installer)
npm run dist:mac   # macOS (.dmg + .zip, Intel + Apple Silicon)
```

成果物は `release/` に出力されます。

> **重要(クロスビルドの制約):** Windows 上から macOS 向けの `.dmg` を作るのは
> 署名/公証の都合で現実的ではありません。**macOS ビルドは macOS 上か CI で行ってください。**
> 本リポジトリには両OSを GitHub Actions でビルドする `.github/workflows/build.yml` を同梱しています
> (タグ `v*` のpush、または手動実行でWindows/macOS両方の成果物を生成)。

## Microsoft ログイン

`msmc` ライブラリを使っており、**Azure アプリ登録などの事前設定は不要**です。
「Sign in with Microsoft」を押すと Microsoft のログイン画面がポップアップし、
普通にサインインするだけでプレミアムアカウントが使えます(オンラインサーバー対応)。
アクセストークンは起動時に保存済みの refresh トークンで自動更新されます。

> 内蔵のクライアントIDはコミュニティ共有方式です。長期安定性を最優先するなら
> 自前の Azure アプリを登録し、`src/main/minecraft/auth/microsoft.js` の
> `new Auth('select_account')` を `new Auth({ client_id, redirect })` に変更してください。

オフラインモードは引き続き設定不要で利用できます。

## トラブルシューティング

### `Electron failed to install correctly` と出て起動しない

`npm install` 後に出る場合、Electron 本体バイナリの展開が失敗しています。
本プロジェクトのパスに **日本語が含まれ、かつ OneDrive 配下** にあると、
`extract-zip` がzip展開に失敗することが確認されています。

対処法(どちらか):

1. **推奨:** プロジェクトを ASCII のみ・OneDrive 外のパスに移動する
   (例: `C:\dev\iea-client`)。これが最も確実です。
2. その場で直す: ダウンロード済みのzipを手動展開する
   ```powershell
   $zip = (Get-ChildItem "$env:LOCALAPPDATA\electron\Cache","$env:TEMP" -Recurse -Filter "electron-v*-win32-x64.zip" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
   $dist = "node_modules\electron\dist"
   Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
   Expand-Archive -Path $zip -DestinationPath $dist -Force
   Set-Content "node_modules\electron\path.txt" -Value "electron.exe" -NoNewline -Encoding ascii
   ```

CI(GitHub Actions)上のランナーは ASCII パスのためこの問題は起きません。

## アイコン

アプリアイコンは `build/icon.svg`(緑グラデの「IEA」バッジ)から生成します。
デザインを変えたら次で全形式(`.png` / `.ico` / `.icns` / 実行時ウィンドウ用)を再生成:

```bash
npm run icons
```

electron-builder が `build/icon.ico`(Win)/ `build/icon.icns`(Mac)を自動で使用します。

## ⚠️ ライセンス / 法的な注意

- このランチャーは Minecraft 本体を**再配布しません**。すべて公式 Mojang サーバーから取得します。
- 改造済みクライアントの配布や Minecraft アセットの再配布は Mojang/Microsoft の EULA に抵触します。
- 個人利用・学習目的での使用にとどめ、正規の Minecraft ライセンス所有を前提としてください。

## ディレクトリ構成

```
src/
  main/                  Electron メインプロセス
    index.js             ウィンドウ生成 + IPC
    settings.js          設定の保存/読込
    minecraft/
      http.js            ダウンロード/SHA1/並列実行ヘルパー
      paths.js           ゲームデータのパス解決
      version.js         バージョンマニフェスト取得
      rules.js           OS判定・ライブラリルール評価
      libraries.js       ライブラリ/ネイティブの解決
      natives.js         ネイティブjarの展開
      assets.js          アセット解決
      java.js            Java実行ファイル検出
      runtime.js         Temurin JRE 8 の自動DL・展開・検出
      launcher.js        ダウンロード→引数組み立て→起動
      auth/
        offline.js       オフラインアカウント
        microsoft.js     Microsoft デバイスコード認証
  preload/preload.js     contextBridge による安全なIPC公開
  renderer/              UI
    index.html           画面構成(data-i18n 属性で多言語化)
    style.css            テーマ(--accent で配色。現在は黄緑)
    i18n.js              翻訳辞書(ja/en)と t()/applyI18n
    renderer.js          UIロジック
```
