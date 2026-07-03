# IEA Client (Java agent)

純正 Minecraft 1.8.9 に**バイトコードインジェクション**で機能を足す Java エージェントです。
Forge等のローダーは使わず、ランチャーが `-javaagent:iea-agent.jar` を付けて起動することで、
一体型の改造クライアントになります。

## 仕組み
- `dev.iea.agent.Agent#premain` がゲームJVM起動前に走り、ASMトランスフォーマーを登録
- `dev.iea.agent.Transformer` が **LWJGL の `Display.update()`**(毎フレーム呼ばれる・難読化されない)の先頭に
  `dev.iea.client.Hook.onFrame()` 呼び出しを注入
- `Hook` が入力(右Shiftトグル)と描画を処理。GUIは `GL11` 直描き＋AWTで焼いたフォントで、
  Minecraftの難読化クラスに依存しない

→ Minecraftのマッピング(難読化名)無しで、レンダースレッド上に安定したフックを得られるのが利点。

## ビルド
ルートから:
```bash
npm run client:build      # = node client/build.mjs
```
- `client/libs/` に ASM と LWJGL を自動DL
- `javac --release 8` でコンパイル(JDK 8以上が必要。出力はJava 8向け)
- ASMをシェードして `client/build/iea-agent.jar` を生成

ランチャーは Settings の「IEAクライアントを有効化」がオンのとき、このjarを自動で注入します。
ゲーム内で **右Shift** を押すとメニュー(clickGUI)が開きます。

## 現状(MVP)
- 右Shiftでモダンなメニュー開閉(ランチャーと同じライム×ダーク配色)
- モジュール行のホバー/クリックでトグル(FPS/CPS/Keystrokes/Fullbright/Sprint/Zoom ※表示のみ)

## 構成
```
src/dev/iea/
  agent/Agent.java         premain / エージェント登録
  agent/Transformer.java   ASM: Display.update() にフック注入
  client/Hook.java         毎フレーム: 入力 + 描画ディスパッチ
  client/Theme.java        配色(ランチャー準拠)
  client/render/Gl.java    GL11 immediate-mode 2D + 状態退避
  client/render/Font.java  AWTで焼くビットマップフォント
  client/gui/ClickGui.java メニュー本体
  client/gui/Module.java   モジュール定義
build.mjs                  ビルドスクリプト(jar不要・mac/win両対応)
```

## 次の一歩(機能を実際に動かす)
モジュールに実処理を付けるには Minecraft 側のフックが要ります。FPS表示などは `Hook.onFrame()` 内で
`Display`/GL から取れる情報で描けますが、CPSやKeystrokesはマウス/キー入力(LWJGL)から取得、
Fullbright/Sprout等はMinecraftクラスへの追加フック(マッピング or 追加トランスフォーマー)が必要です。
