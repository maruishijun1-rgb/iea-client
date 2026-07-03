package dev.iea.client;

import java.util.HashMap;
import java.util.Map;

/** In-game UI translations (Japanese / English). */
public final class Lang {
    public static String current = "ja";

    private static final Map<String, String> JA = new HashMap<String, String>();
    private static final Map<String, String> EN = new HashMap<String, String>();

    private static void put(String k, String ja, String en) { JA.put(k, ja); EN.put(k, en); }

    static {
        put("tab_all", "すべて", "All");
        put("cat_hud", "HUD", "HUD");
        put("cat_input", "入力", "Input");
        put("cat_render", "描画", "Render");
        put("cat_player", "プレイヤー", "Player");
        put("enabled", "有効", "Enabled");
        put("category", "カテゴリ", "Category");
        put("reset_pos", "この表示の位置をリセット", "Reset position");
        put("edit_hud", "HUD編集", "Edit HUD");
        put("back", "戻る", "Back");
        put("search", "検索...", "Search...");
        put("on", "オン", "ON");
        put("off", "オフ", "OFF");
        put("hud_hint", "ドラッグで移動  /  右Shiftで完了", "drag to move  /  Right Shift to finish");
        put("note_pos", "HUDの位置は「HUD編集」からドラッグで移動できます。",
                "Drag items in Edit HUD to move them.");
        // setting names
        put("s.scale", "大きさ", "Size");
        put("s.opacity", "透明度", "Opacity");
        put("s.label", "ラベルを表示", "Show label");
        put("s.split", "左右を分けて表示", "Split L / R");
        put("s.h24", "24時間表示", "24-hour clock");
        put("s.sec", "秒を表示", "Show seconds");
        put("s.bg", "背景を表示", "Show background");
        put("s.space", "スペースバーを表示", "Show spacebar");
        put("s.percent", "パーセント表示", "Show percent");
        put("s.cps", "CPSを表示", "Show CPS");
        put("s.mouse", "マウスクリックを表示", "Show mouse clicks");
        put("s.type", "タイプ", "Type");
        put("s.horiz", "横に並べる", "Horizontal layout");
        put("s.smoothsel", "選択スロットをなめらかに移動", "Smooth slot transition");
        put("s.statbars", "体力・満腹度・防具をバーで表示", "Health/hunger/armor as bars");
        put("s.grow", "伸びる向きを反転", "Flip grow direction");
        put("s.dot", "中央ドット", "Center dot");
        put("s.zoom", "ズーム倍率", "Zoom factor");
        put("s.red", "赤", "Red");
        put("s.green", "緑", "Green");
        put("s.blue", "青", "Blue");
        put("s.alpha", "不透明度", "Alpha");
        put("s.width", "線の太さ", "Line width");
        put("s.key", "キー", "Key");
        put("s.count", "数", "Count");
        put("s.speed", "速さ", "Speed");
        put("s.amount", "強さ", "Amount");
        put("s.saturation", "彩度 (0=白黒 1=通常 2=鮮やか)", "Saturation (0=grey, 1=normal, 2=vivid)");
        put("s.red", "赤 (R)", "Red (R)");
        put("s.green", "緑 (G)", "Green (G)");
        put("s.blue", "青 (B)", "Blue (B)");
        put("s.seconds", "表示時間(秒)", "Duration (sec)");
        put("s.debug", "デバッグログ", "Debug log");
        put("s.eyeline", "視線を表示", "Show eye line");
        put("s.leveltype", "種類", "Type");
        put("s.lvl_network", "ネットワーク", "Network");
        put("s.lvl_bedwars", "Bedwarsスター", "Bedwars star");
        put("s.lvl_skywars", "SkyWarsレベル", "SkyWars level");
        put("s.lvl_fkdr", "Bedwars FKDR", "Bedwars FKDR");
        put("s.lvl_wins", "Bedwars 勝利数", "Bedwars wins");
        put("s.lvl_self", "自分にも表示(三人称)", "Show on yourself (3rd person)");
        // Crosshair type options
        put("s.cx_cross", "十字", "Cross");
        put("s.cx_gap", "隙間", "Gap");
        put("s.cx_dot", "ドット", "Dot");
        put("s.cx_t", "T字", "T-shape");
        put("s.cx_circle", "円", "Circle");
        put("s.cx_square", "四角", "Square");
        put("s.cx_x", "X", "X");
        put("s.cx_arrow", "矢印", "Arrow");
        // HitParticles type options
        put("s.hp_magic", "シャープネス(マジック)", "Sharpness (magic)");
        put("s.hp_crit", "クリティカル", "Critical");
        put("s.ip_height", "高さ", "Height");
        put("s.ip_depth", "前後位置", "Depth");
        // SoundTuner per-sound volume labels
        put("s.snd_explode", "爆発", "Explosion");
        put("s.snd_eat", "食べる", "Eating");
        put("s.snd_drink", "飲む", "Drinking");
        put("s.snd_levelup", "レベルアップ", "Level up");
        put("s.snd_orb", "経験値オーブ", "XP orb");
        put("s.snd_pop", "アイテム取得", "Item pickup");
        put("s.snd_bow", "弓/パール投げ", "Bow / pearl");
        put("s.snd_hurt", "ダメージ", "Hurt");
        put("s.snd_fire", "着火", "Fire ignite");
        put("s.snd_portal", "エンダーパール移動", "Ender teleport");
        put("s.snd_anvil", "金床", "Anvil");
        put("s.snd_click", "クリック/ボタン", "Click / button");
        // Hitbox per-category colours (R/G/B)
        put("s.hb_en_r", "敵 赤", "Enemy R");   put("s.hb_en_g", "敵 緑", "Enemy G");   put("s.hb_en_b", "敵 青", "Enemy B");
        put("s.hb_te_r", "味方 赤", "Teammate R"); put("s.hb_te_g", "味方 緑", "Teammate G"); put("s.hb_te_b", "味方 青", "Teammate B");
        put("s.hb_mo_r", "動物 赤", "Mob R");    put("s.hb_mo_g", "動物 緑", "Mob G");    put("s.hb_mo_b", "動物 青", "Mob B");
        put("s.hb_ar_r", "矢 赤", "Arrow R");    put("s.hb_ar_g", "矢 緑", "Arrow G");    put("s.hb_ar_b", "矢 青", "Arrow B");
        put("s.hb_pe_r", "パール 赤", "Pearl R");  put("s.hb_pe_g", "パール 緑", "Pearl G");  put("s.hb_pe_b", "パール 青", "Pearl B");
        put("s.hb_pr_r", "投擲物 赤", "Projectile R"); put("s.hb_pr_g", "投擲物 緑", "Projectile G"); put("s.hb_pr_b", "投擲物 青", "Projectile B");
        put("s.hb_ot_r", "その他 赤", "Other R");  put("s.hb_ot_g", "その他 緑", "Other G");  put("s.hb_ot_b", "その他 青", "Other B");
        put("death_down", "が倒れた", " is down");
        put("s.swing", "使用中もスイング", "Swing while using item");
        put("s.sneak", "しゃがみを滑らかに", "Smooth sneak camera");
        put("s.equip", "アイテム切替モーション無効", "No item-switch animation");
        put("press_key", "キーを押す...", "Press a key...");
        // module descriptions
        put("d.watermark", "画面にIEAのロゴを表示します。", "Shows the IEA logo on screen.");
        put("d.fps", "現在のフレームレート(FPS)を表示します。", "Shows your current framerate (FPS).");
        put("d.cps", "1秒あたりのクリック回数(CPS)を表示します。", "Shows your clicks per second (CPS).");
        put("d.clock", "現在の時刻を表示します。", "Shows the current time of day.");
        put("d.memory", "メモリ(RAM)の使用量を表示します。", "Shows current RAM usage.");
        put("d.arraylist", "有効になっている機能の一覧を表示します。", "Lists the modules you have enabled.");
        put("d.session", "プレイ開始からの経過時間を表示します。", "Shows time elapsed since you started playing.");
        put("d.combo", "連続で攻撃を当てた回数(コンボ)を表示します。一定時間あたらないとリセットされます。",
                "Shows how many hits you have landed in a row. Resets after a short gap.");
        put("d.serveraddr", "接続中のサーバーのアドレスを表示します。",
                "Shows the address of the server you are connected to.");
        put("d.deathalert", "ベッドウォーズで味方(同じチーム)が死んだとき、画面中央にタイトルで知らせます。Hypixel専用。",
                "On Bedwars, shows a centered title when a teammate (same team) dies. Hypixel only.");
        put("d.keystrokes", "WASD・スペースの入力を表示します。", "Shows your WASD / space key presses.");
        put("d.mousestrokes", "マウスの動きをインジケーターで表示します。", "Shows your mouse movement on a pad.");
        put("d.crosshair", "カスタムクロスヘアを表示します。タイプ: 0=十字 1=隙間 2=ドット 3=T 4=円 5=四角 6=X 7=矢印",
                "Custom crosshair. Types: 0=cross 1=gap 2=dot 3=T 4=circle 5=square 6=X 7=arrow");
        put("d.fullbright", "暗い場所でも明るく見えるようにします。", "Brightens dark areas to full light.");
        put("d.zoom", "キーを押している間、視界をズームします。", "Zooms your view while the key is held.");
        put("d.togglesprint", "キーを押すとダッシュのオン/オフを切り替えます(押しっぱなし不要)。既定はLeft Ctrl。",
                "Press the key to toggle sprint on/off (no need to hold). Default: Left Ctrl.");
        put("d.blockoverlay", "見ているブロックを色付きの枠で強調します。", "Highlights the block you look at with a colored box.");
        put("d.hitbox", "マイクラ本来の当たり判定(F3+Bと同じデータ)の枠を表示します。敵/味方/動物/矢/パール/投擲物/その他ごとに色を変えられ、視線のオン/オフもできます。",
                "Draws each entity's real (vanilla F3+B) hitbox. Colour is configurable per category (enemy/teammate/mob/arrow/pearl/projectile/other), with an optional eye-line.");
        put("d.hitparticles", "攻撃を当てたとき、本物のバニラのパーティクルを表示します。タイプ: 0=シャープネス(マジッククリット) / 1=クリティカル。数を変更できます。",
                "Spawns real vanilla particles when you land a hit. Type: 0 = sharpness (magic crit) / 1 = crit. Count is adjustable.");
        put("d.tnttimer", "起爆したTNTの爆発までの残り秒数を表示します。", "Shows the countdown above primed TNT.");
        put("d.levelhead", "Hypixelで他プレイヤーのレベル等をネームタグの名前の横に表示します。種類: ネットワーク/Bedwarsスター/SkyWarsレベル/Bedwars FKDR/Bedwars勝利数。ランチャーの設定にHypixel APIキーを入れてください。",
                "On Hypixel, shows a player's level next to their name. Types: Network / Bedwars star / SkyWars level / Bedwars FKDR / Bedwars wins. Set your Hypixel API key in the launcher settings.");
        put("d.itemphysics", "落ちているアイテムを地面に平らに倒し、回転・上下の動きを止めて静止させます。",
                "Lays dropped items flat on the ground and freezes their spin/bob so they rest still.");
        put("d.soundtuner", "特定のサウンドの音量を個別に調整します(0=ミュート〜大きく)。倍率なので、小さい/遠い音は上げると聞こえやすくなります(エンジン上限あり)。",
                "Adjusts the volume of specific sounds individually (0 = mute .. louder). It's a multiplier, so quiet/distant sounds get more audible when raised (engine-capped).");
        put("d.motionblur", "動かしたときに残像(モーションブラー)を出します。強さで残像の量を調整できます。",
                "Adds a motion-blur trail as the view moves. Use Amount to tune the strength.");
        put("d.saturation", "画面全体の彩度を変えます。0で白黒、1で通常、2で鮮やか。",
                "Adjusts the whole screen's saturation. 0 = greyscale, 1 = normal, 2 = vivid.");
        put("d.nofov", "ダッシュや弓・アイテム使用によるFOV(視野)の変化をなくし、常に一定に保ちます。手の見た目はバニラのまま変わりません。",
                "Removes the FOV zoom from sprinting / speed / using a bow, keeping the view a fixed FOV. The hand/viewmodel stays exactly vanilla.");
        put("d.theme", "IEAのUI(GUI・HUD)のアクセント色を変更します。R/G/Bで好きな色に。オフで既定のライムに戻ります。",
                "Changes the IEA UI (GUI/HUD) accent colour. Set R/G/B to any colour; turn off to restore the default lime.");
        put("d.oldanim", "アイテム使用中でも腕を振れるなど、1.7風のモーションにします。",
                "1.7-style animations, e.g. swinging while using an item.");
        put("d.reach", "最後に攻撃が当たった相手との距離(リーチ)を表示します。",
                "Shows the distance of your last hit.");
        put("d.coords", "現在の座標(X/Y/Z)と向いている方角を表示します。",
                "Shows your coordinates and facing direction.");
        put("d.ping", "サーバーへのPing(応答時間)を表示します。",
                "Shows your ping to the server.");
        put("d.potionhud", "現在かかっているポーション効果と残り時間を表示します。",
                "Shows your active potion effects and time left.");
        put("d.nohurtcam", "ダメージを受けた時の画面の揺れをなくします。",
                "Removes the camera shake when you take damage.");
        put("d.swingspeed", "腕を振るアニメーションの速度を上げます。",
                "Speeds up your arm-swing animation.");
        put("d.hitcolor", "敵にダメージを与えたときの点滅(赤)の色を変更します。",
                "Changes the colour of the damage flash on entities you hit.");
        put("d.customsky", "リソースパックのカスタム空(OptiFine/MCPatcher形式)を反映します。※現在は描画位置の確認用に空を一色で塗ります。",
                "Applies resource-pack custom skies (OptiFine/MCPatcher). Currently paints the sky a flat colour to confirm the hook.");
        put("d.armor", "防具と手持ちアイテムの耐久値を表示します。",
                "Shows durability of your armor and held item.");
        put("d.hotbar", "ホットバーをIEAのデザインに変更します。",
                "Restyles the hotbar to the IEA design.");
        put("d.ieafont", "マイクラ全体とIEAのGUI/HUDの文字をIEAフォントに変更します。オフにするとGUIもバニラフォントに戻り、ゲームと表示が統一されます。",
                "Switches both Minecraft's text and the IEA GUI/HUD to the IEA font. Turn it off and the GUI reverts to the vanilla font too, so the game and UI always match.");
        put("d.ieagui", "タイトル画面とボタンをIEAのデザインに変更します。オフにするとバニラ風の見た目に戻ります。",
                "Restyles the title screen and buttons to the IEA design. Turn off for a vanilla look.");
    }

    public static String t(String key) {
        Map<String, String> m = "en".equals(current) ? EN : JA;
        String v = m.get(key);
        if (v != null) return v;
        v = JA.get(key);
        return v != null ? v : key;
    }

    public static void toggle() {
        current = "ja".equals(current) ? "en" : "ja";
    }

    /** Short label for the language button. */
    public static String badge() {
        return "ja".equals(current) ? "JP" : "EN";
    }
}
