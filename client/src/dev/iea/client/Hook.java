package dev.iea.client;

import java.util.ArrayDeque;
import java.util.Deque;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import dev.iea.client.module.Module;
import dev.iea.client.render.Gl;

import dev.iea.client.gui.ClickGui;
import dev.iea.client.hud.HudManager;
import dev.iea.client.module.Modules;
import dev.iea.client.render.Font;
import dev.iea.client.render.Logo;
import dev.iea.client.render.Cobble;
import dev.iea.client.render.Sky;
import dev.iea.client.render.Surface;
import dev.iea.client.render.MotionBlur;
import dev.iea.client.render.Saturation;

/**
 * Per-frame entry from inside Display.update(). Handles toggles, FPS/CPS, input
 * blocking, and dispatches HUD + menu rendering. The OS cursor is used while the
 * menu is open (mouse ungrabbed); camera/clicks/keys are swallowed.
 */
public final class Hook {
    private static boolean inited = false;
    private static ClickGui gui;
    private static HudManager hud;
    private static Font rowFont, titleFont, bigFont, hudFont;
    private static Logo logo;

    private static boolean prevToggle = false, prevEsc = false, prevDump = false;

    private static int frames = 0, fps = 0;
    private static long lastFpsTime = 0;

    private static final Deque<Long> leftClicks = new ArrayDeque<Long>();
    private static final Deque<Long> rightClicks = new ArrayDeque<Long>();

    // 1.8.9 GameSettings field names (universal for the official obfuscated jar)
    private static final String GAMMA = "aJ";
    private static boolean fullbrightOn = false;
    private static float savedGamma = 0.5f;
    private static boolean sprintForced = false;
    private static boolean sprintToggled = false; // ToggleSprint latch state
    private static boolean prevSprintKey = false;  // edge-detect for the toggle key

    // Zoom + NoFov: render-side FOV filter (EntityRenderer.getFOVModifier return value).
    // gameSettings is never written, the hand keeps its FOV (useFovSetting==false path is
    // untouched, so the arm/viewmodel is exactly vanilla), and the zoom factor eases.
    private static final String FOV = "aI"; // GameSettings.fovSetting (see gamma = aJ)
    private static float zoomTarget = 1f, zoomCur = 1f;
    public static float filterFov(float v, boolean useFovSetting) {
        float f = v;
        try {
            // NoFov: return the plain FOV setting so sprint/speed/bow don't zoom the world.
            // Only the world FOV (useFovSetting) is affected; the hand FOV stays vanilla.
            if (useFovSetting && Modules.on("NoFov")) {
                float base = Mc.getSettingFloat(FOV, v);
                if (base > 1f) f = base; // guard a bad/normalised read
            }
        } catch (Throwable ignored) { }
        if (useFovSetting && zoomCur > 1.001f) f = f / zoomCur;
        return f;
    }

    public static boolean isGuiOpen() { return gui != null && gui.isOpen(); }
    public static boolean blockGameInput() { return isGuiOpen(); }

    private static int clampByte(float v) { return v < 0 ? 0 : (v > 255 ? 255 : (int) v); }

    // agent filters: swallow camera/clicks/keys while the menu is open
    private static float mvX, mvY; // accumulated mouse movement (decays) for MouseStrokes
    public static int filterDX(int v) { mvX += v; return blockGameInput() ? 0 : v; }
    public static int filterDY(int v) { mvY += v; return blockGameInput() ? 0 : v; }
    public static float moveX() { return mvX; }
    public static float moveY() { return mvY; }
    private static int scrollAccum = 0;
    // block the hotbar wheel while the menu is open; the menu's own scroll is read
    // straight from the event queue during the drain below (more reliable than
    // depending on Minecraft to poll getDWheel while the mouse is ungrabbed).
    public static int filterWheel(int v) { return blockGameInput() ? 0 : v; }

    /** getEventDWheel: the wheel path while a GuiScreen is open. Feed our chat scroll only when
     *  the CHAT screen is open (so inventory/creative scrolling is untouched); pass the value on. */
    public static int filterEventWheel(int v) {
        if (v != 0) {
            try {
                if (inited && chatOverlayOn() && Mc.isChatOpen())
                    dev.iea.client.render.ChatOverlay.onWheel(v);
            } catch (Throwable ignored) { }
        }
        return v;
    }
    private static boolean draining = false;
    public static boolean filterEvent(boolean v) {
        if (draining) return v;        // let our own drain loop see real events
        // Count clicks from the mouse EVENT QUEUE (every press is seen here, since the game
        // drains all pending events each tick) instead of polling once per frame — polling
        // caps CPS at the frame rate, so a 255 CPS auto-clicker read as ~FPS (e.g. 77).
        if (v && !blockGameInput()) {
            try {
                if (Mouse.getEventButtonState()) { // a press (not a release / move / wheel)
                    int btn = Mouse.getEventButton();
                    long now = System.currentTimeMillis();
                    if (btn == 0) leftClicks.add(now);
                    else if (btn == 1) rightClicks.add(now);
                }
            } catch (Throwable ignored) { }
        }
        return blockGameInput() ? false : v;
    }
    // Keyboard.next() specifically: the native call consumes the event from the shared
    // queue even when we hide it from the game (return false). If the game polls the
    // queue before our per-frame drain, that event would be lost — so capture it here
    // (feed the menu's search box) so nothing is dropped no matter who reads it first.
    public static boolean filterKeyEvent(boolean v) {
        if (draining) return v;            // our drain loop feeds the search box itself
        if (!blockGameInput()) return v;   // menu closed: pass the event to the game
        if (v) {
            try {
                if (Keyboard.getEventKeyState() && gui != null)
                    gui.onCharTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
            } catch (Throwable ignored) { }
        }
        return false;                      // hide it from the game
    }
    public static boolean filterGrab(boolean req) { return blockGameInput() ? false : req; } // ungrab -> OS cursor shows

    // GuiIngame.showCrosshair(): hide the vanilla crosshair while ours is enabled
    public static boolean filterShowCrosshair(boolean v) {
        try { return (v && Modules.on("Crosshair")) ? false : v; } catch (Throwable t) { return v; }
    }

    // PlayerControllerMP.attackEntity entry: record the reach of this hit (distance
    // from the attacker's eye to the closest point of the target's bounding box).
    // ComboCounter: consecutive entity hits; resets after a short idle gap.
    private static int comboCount = 0;
    private static long comboLastMs = 0;
    private static final long COMBO_TIMEOUT_MS = 2500;
    public static int combo() {
        if (comboCount > 0 && System.currentTimeMillis() - comboLastMs > COMBO_TIMEOUT_MS) comboCount = 0;
        return comboCount;
    }

    public static float lastReach = -1f;
    public static void onAttack(Object player, Object target) {
        try {
            comboCount++;                              // ComboCounter: count this hit
            comboLastMs = System.currentTimeMillis();
            double[] tb = Mc.aabb(Mc.entityBox(target));
            double[] pb = Mc.aabb(Mc.entityBox(player));

            // HitParticles: spawn REAL vanilla crit / magic-crit particles at the target,
            // through the discovered EffectRenderer (no custom rendering).
            Module hp = Modules.get("HitParticles");
            if (hp != null && hp.enabled && tb != null) {
                int count = (int) hp.num("count", 12f);
                int id = ((int) hp.num("type", 0f) == 1) ? 9 : 10; // 0=magicCrit(sharpness) 1=crit
                Mc.spawnHitParticles((tb[0] + tb[3]) / 2.0, (tb[1] + tb[4]) / 2.0, (tb[2] + tb[5]) / 2.0,
                        tb[3] - tb[0], tb[4] - tb[1], count, id);
            }

            if (tb == null || pb == null) return;
            double ex = (pb[0] + pb[3]) / 2.0, ey = pb[1] + 1.62, ez = (pb[2] + pb[5]) / 2.0;
            double cx = Math.max(tb[0], Math.min(ex, tb[3]));
            double cy = Math.max(tb[1], Math.min(ey, tb[4]));
            double cz = Math.max(tb[2], Math.min(ez, tb[5]));
            lastReach = (float) Math.sqrt((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy) + (ez - cz) * (ez - cz));
        } catch (Throwable ignored) { }
    }

    // --- OldAnimations: ItemRenderer hooks --------------------------------
    // equip: every (F)V method on ItemRenderer is wrapped. On the OUTERMOST entry we
    // force equippedProgress to 1 so the held item never dips on a slot switch, and
    // restore it on the outermost exit. A depth counter handles nested (F)V calls;
    // irDepth is also reset each frame in case a return was skipped via exception.
    private static int irDepth = 0;
    private static boolean irEquip = false;
    public static void itemRenderEnter() {
        try {
            if (irDepth == 0) {
                irEquip = false;
                Module oa = Modules.get("OldAnimations");
                if (oa != null && oa.enabled && oa.bool("equip") && Mc.equipDrawEngage())
                    irEquip = true;
            }
        } catch (Throwable ignored) { }
        irDepth++;
    }
    public static void itemRenderExit() {
        try {
            irDepth--;
            if (irDepth <= 0) {
                irDepth = 0;
                if (irEquip) { Mc.equipDrawRestore(); irEquip = false; }
            }
        } catch (Throwable ignored) { }
    }

    // swing: transformFirstPersonItem(equipProgress, swingProgress) is called with
    // swingProgress == 0 while you use an item (eat/drink/block/bow). When OldAnimations
    // is on we feed in our own swing value so the swing rotation plays ON TOP of the
    // use pose (1.7 "block hit" style). The value is driven as a per-click CYCLE
    // (computed in onFrame, see updateOldAnimSwing) so a quick tap still plays one full
    // 0->1 swing and a hold repeats it. The swing==0 guard uniquely targets this call
    // (the other (FF)V method is a pitch/yaw rotation whose 2nd arg is essentially
    // never exactly 0).
    private static final long SWING_PERIOD_MS = 280;
    private static long swingCycleStart = 0;
    private static boolean swingActive = false;
    private static boolean prevLeftSwing = false;
    private static float swingValue = -1f; // -1 = don't override

    // Apply the cycle's swing only while actually using an item (so the swing carries
    // over the moment you start right-clicking mid-swing, and so we never fight vanilla's
    // own swing when not using). swing==0 marks the use-branch transformFirstPersonItem.
    public static float swingArg(float equip, float swing) {
        try {
            if (swing == 0f && swingValue >= 0f && Mc.isUsingItem()) return swingValue;
        } catch (Throwable ignored) { }
        return swing;
    }

    // Drive the swing cycle independently of item use. ANY left-click starts a fresh
    // 0->1 cycle (so an air/entity punch plays exactly one swing), and it runs to
    // completion even if released (tap = one full swing). It only REPEATS while the
    // button is held on a block (mining = continuous). Because the cycle keeps running
    // regardless of use, pressing right-click mid-swing carries the swing over from its
    // current midpoint (swingArg starts applying swingValue once item-use begins).
    private static void updateOldAnimSwing(boolean leftDown, long now) {
        swingValue = -1f;
        Module oa = Modules.get("OldAnimations");
        boolean on = oa != null && oa.enabled && oa.bool("swing") && !isGuiOpen();
        if (!on) { swingActive = false; prevLeftSwing = leftDown; return; }

        if (leftDown && !prevLeftSwing) { swingActive = true; swingCycleStart = now; } // any click
        prevLeftSwing = leftDown;

        if (swingActive) {
            boolean onBlock = leftDown && Mc.isLookingAtBlock();
            long elapsed = now - swingCycleStart;
            if (elapsed >= SWING_PERIOD_MS) {
                if (onBlock) { swingCycleStart = now; elapsed = 0; } // hold on block -> next cycle
                else { swingActive = false; }                       // air/tap -> one cycle only
            }
            if (swingActive) swingValue = elapsed / (float) SWING_PERIOD_MS;
        }
    }

    // --- OldAnimations: smooth sneak camera -------------------------------
    // getEyeHeight() (one of the player's ()->float methods) drops instantly when you
    // sneak, snapping the camera. We intercept its return value and hand back a value
    // that eases toward the real one, so the camera height changes smoothly (1.7 feel).
    // Identification is by VALUE (eye height ~1.62) + caller (must be the local player),
    // so we never touch unrelated getters or other entities.
    // confirmed obf behaviour: getEyeHeight() returns 1.62 standing / 1.54 sneaking,
    // and snaps in one frame. We ease that value over ~0.3s so the camera glides.
    private static Object playerRef;       // cached once per frame for the self check
    private static boolean eyeKnown = false;
    private static float smoothEye, targetEye;
    private static long lastSneakTime = 0;
    private static final float SNEAK_TAU = 0.2f; // ease time-constant (seconds)

    public static float eyeHeightFilter(float v, Object self) {
        try {
            if (playerRef == null || self != playerRef) return v;  // only the local player
            // NoHurtCam: zero hurtTime HERE because this getter runs every frame BEFORE
            // the hurt camera effect (via getMouseOver -> getPositionEyes), so the shake
            // never renders — zeroing only in onFrame left a 1-frame jolt.
            if (Modules.on("NoHurtCam")) Mc.zeroHurtTime();
            Module oa = Modules.get("OldAnimations");
            if (oa == null || !oa.enabled || !oa.bool("sneak")) { eyeKnown = false; return v; }
            if (v < 1.4f || v > 1.7f) return v;                    // not the eye height
            targetEye = v;                                         // vanilla drop amount, just eased
            if (!eyeKnown) { smoothEye = v; eyeKnown = true; }
            return smoothEye;
        } catch (Throwable t) { return v; }
    }
    // ease the smoothed eye height toward the real value (frame-rate independent).
    // The ease time-constant is adjustable (smaller = snappier, larger = smoother).
    private static void updateSneakSmooth() {
        playerRef = Mc.localPlayer();
        long t = System.nanoTime();
        if (lastSneakTime == 0) lastSneakTime = t;
        float dt = (t - lastSneakTime) / 1.0e9f;
        lastSneakTime = t;
        if (eyeKnown && dt > 0f) {
            float a = 1f - (float) Math.exp(-dt / SNEAK_TAU);
            smoothEye += (targetEye - smoothEye) * a;
            if (Math.abs(targetEye - smoothEye) < 0.0005f) smoothEye = targetEye;
        }
        if (dt > 0f) { // ease the zoom factor (~80ms time constant)
            float za = 1f - (float) Math.exp(-dt / 0.08f);
            zoomCur += (zoomTarget - zoomCur) * za;
            if (Math.abs(zoomTarget - zoomCur) < 0.005f) zoomCur = zoomTarget;
        }
    }

    // Set from Minecraft.drawSplashScreen entry; consumed on the next onFrame (which
    // fires from that method's updateDisplay call) to overpaint the Mojang logo.
    private static boolean splashActive = false;
    public static void onSplash() { splashActive = true; }

    // full-screen IEA splash: dark background + centered IEA badge, replacing Mojang.
    private static void drawSplash() {
        try {
            if (logo == null) { logo = new Logo(); logo.init(128); }
            int w = Display.getWidth(), h = Display.getHeight();
            Surface.begin(w, h);
            try {
                Gl.alpha = 1f;
                Gl.rect(0, 0, w, h, 0xFF0E1116);        // dark cover over the Mojang logo
                float size = Math.min(w, h) * 0.30f;
                logo.draw((w - size) / 2f, (h - size) / 2f, size);
            } finally {
                Surface.end(w, h);
            }
        } catch (Throwable ignored) { }
    }

    // --- IEAFont: replace the vanilla FontRenderer with our font ------------
    // Hooks the drawString funnel + getStringWidth/getCharWidth, so every piece of
    // vanilla text (chat, tooltips, menus, F3...) renders AND measures with the IEA
    // font; -1 means "not handled, use vanilla".
    private static Font mcFont;
    private static float mcFontScale;
    private static boolean fontOn; // cached once per frame (these hooks are hot)
    private static final int[] MC_COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };

    // chars neither Yu Gothic nor the system fallback can display (e.g. servers'
    // private-use glyph hacks) are rendered INLINE by the vanilla renderer: the
    // string is split into IEA / vanilla runs, both draw and width use the same
    // split, so the layout stays consistent. fontBypass marks our own re-entrant
    // calls into the (hooked) vanilla methods.
    private static final java.util.BitSet fontKnown = new java.util.BitSet(65536);
    private static final java.util.BitSet fontOk = new java.util.BitSet(65536);
    private static boolean fontBypass;
    private static java.lang.reflect.Method frDrawM, frWidthM;

    private static boolean fontHasChar(char c) {
        if (!fontKnown.get(c)) {
            fontKnown.set(c);
            // Emoji-presentation chars render as colour emoji even in Yu Gothic (e.g.
            // U+26BD = soccer ball), so let the vanilla font draw those. Plain text
            // symbols (★ ☆ ♥ ♠ …) are NOT emoji-presentation and stay in the IEA font.
            if (!isEmojiPresentation(c) && mcFont.canDisplay(c)) fontOk.set(c);
        }
        return fontOk.get(c);
    }

    // Unicode "Emoji_Presentation = Yes" within the BMP (plus astral surrogates and
    // variation selectors). These default to a colour-emoji glyph; we route them to
    // the vanilla renderer so IEAFont never shows e.g. a soccer ball in a scoreboard.
    private static boolean isEmojiPresentation(char c) {
        if (c >= '\uD800' && c <= '\uDFFF') return true; // surrogates (emoji planes)
        if (c >= '︀' && c <= '️') return true; // variation selectors
        if (c >= '⌚' && c <= '⌛') return true;
        if (c >= '⏩' && c <= '⏬') return true;
        if (c == '⏰' || c == '⏳') return true;
        if (c >= '◽' && c <= '◾') return true;
        if (c >= '☔' && c <= '☕') return true;
        if (c >= '♈' && c <= '♓') return true; // zodiac
        if (c == '♿' || c == '⚓' || c == '⚡') return true;
        if (c >= '⚪' && c <= '⚫') return true;
        if (c >= '⚽' && c <= '⚾') return true; // U+26BD = soccer ball
        if (c >= '⛄' && c <= '⛅') return true;
        if (c == '⛎' || c == '⛔' || c == '⛪') return true;
        if (c >= '⛲' && c <= '⛳') return true;
        if (c == '⛵' || c == '⛺' || c == '⛽') return true;
        if (c == '✅') return true;
        if (c >= '✊' && c <= '✋') return true;
        if (c == '✨' || c == '❌' || c == '❎') return true;
        if (c >= '❓' && c <= '❕') return true;
        if (c == '❗') return true;
        if (c >= '➕' && c <= '➗') return true;
        if (c == '➰' || c == '➿') return true;
        if (c >= '⬛' && c <= '⬜') return true;
        return c == '⭐' || c == '⭕';
    }

    // split into runs: {text (no § codes), rgb (-1 = base colour), vanilla?}
    private static java.util.List<Object[]> fontTokens(String s) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        StringBuilder seg = new StringBuilder();
        int rgb = -1;
        boolean segVan = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '§' && i < n - 1) {
                if (seg.length() > 0) {
                    out.add(new Object[] { seg.toString(), Integer.valueOf(rgb), Boolean.valueOf(segVan) });
                    seg.setLength(0);
                }
                char k = Character.toLowerCase(s.charAt(++i));
                int idx = "0123456789abcdef".indexOf(k);
                if (idx >= 0) rgb = MC_COLORS[idx];
                else if (k == 'r') rgb = -1;
                continue;
            }
            boolean van = c != ' ' && !fontHasChar(c);
            if (seg.length() > 0 && van != segVan) {
                out.add(new Object[] { seg.toString(), Integer.valueOf(rgb), Boolean.valueOf(segVan) });
                seg.setLength(0);
            }
            segVan = van;
            seg.append(c);
        }
        if (seg.length() > 0)
            out.add(new Object[] { seg.toString(), Integer.valueOf(rgb), Boolean.valueOf(segVan) });
        return out;
    }

    private static int tokColor(int base, int rgb, boolean shadowPass) {
        int c = (rgb < 0) ? base : ((base & 0xFF000000) | rgb);
        return shadowPass ? ((c & 0xFCFCFC) >> 2 | (c & 0xFF000000)) : c;
    }

    // the vanilla drawString funnel / getStringWidth on the FontRenderer, found by
    // descriptor; two methods share (String,F,F,I,Z)I — take the name-smallest ("a"
    // = the public funnel; "b" is the internal renderString)
    private static void ensureFrMethods(Object fr) {
        if (frDrawM != null && frWidthM != null) return;
        for (java.lang.reflect.Method m : fr.getClass().getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != int.class) continue;
            if (p.length == 5 && p[0] == String.class && p[1] == float.class && p[2] == float.class
                    && p[3] == int.class && p[4] == boolean.class) {
                if (frDrawM == null || m.getName().compareTo(frDrawM.getName()) < 0) {
                    m.setAccessible(true); frDrawM = m;
                }
            } else if (p.length == 1 && p[0] == String.class) {
                m.setAccessible(true); frWidthM = m;
            }
        }
    }

    private static int vanillaWidth(Object fr, String s) {
        fontBypass = true;
        try { return ((Integer) frWidthM.invoke(fr, s)).intValue(); }
        catch (Throwable t) { return 0; }
        finally { fontBypass = false; }
    }

    // --- ChatOptimize: custom chat renderer (replaces GuiNewChat.drawChat) -----
    public static boolean chatOptimizeOn() {
        try { return inited && Modules.on("ChatOptimize"); } catch (Throwable t) { return false; }
    }

    /** Translator (per-line translate button) is on. Uses the same custom chat renderer. */
    public static boolean translatorOn() {
        try { return inited && Modules.on("Translator"); } catch (Throwable t) { return false; }
    }

    /** The custom chat renderer is active when EITHER chat feature is on (they share it). */
    public static boolean chatOverlayOn() { return chatOptimizeOn() || translatorOn(); }

    /** Selected target language code for the Translator, e.g. "ja" (default). */
    public static String translateLang() {
        try {
            Module m = Modules.get("Translator");
            int i = (m == null) ? 0 : (int) m.num("lang", 0);
            String[] codes = Modules.TR_LANGS;
            return (i >= 0 && i < codes.length) ? codes[i] : "ja";
        } catch (Throwable t) { return "ja"; }
    }

    /** Translator "items": rewrite item-tooltip lines to the target language in place. Called
     *  from GuiScreen.drawHoveringText at entry. Async + cached — a line stays original until
     *  its translation is ready, then flips to it (leading § colour codes are preserved). */
    public static void translateTooltip(java.util.List lines) {
        try {
            if (!inited || !translatorOn() || lines == null || lines.isEmpty()) return;
            Module m = Modules.get("Translator");
            if (m == null || !m.bool("items")) return;
            String lang = translateLang();
            for (int i = 0; i < lines.size(); i++) {
                Object o = lines.get(i);
                if (!(o instanceof String)) continue;
                String line = (String) o;
                String stripped = Mc.stripFormatting(line);
                if (stripped == null || stripped.trim().isEmpty()) continue;
                String tr = dev.iea.client.Translate.get(stripped, lang);
                if (tr == null || tr.isEmpty() || tr.equals(stripped)) continue; // pending/failed/same
                lines.set(i, leadingCodes(line) + tr);
            }
        } catch (Throwable ignored) { }
    }

    // The run of leading "§x" formatting codes at the start of a line (kept on the translation
    // so an item name's rarity colour etc. survives).
    private static String leadingCodes(String s) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i + 1 < s.length() && s.charAt(i) == '§') { b.append('§').append(s.charAt(i + 1)); i += 2; }
        return b.toString();
    }

    private static int chatCalls = 0;
    private static boolean prevVScreen = false; // tracks chat-screen open/close for chat scroll reset
    private static boolean prevChatKey = false, chatKeyArmed = false; // learn the chat-screen class
    private static int hudScaledW = 0, hudScaledH = 0; // real scaled resolution, cached in drawHotbar
    /** Draw our chat inside onFrame's Surface pass (where GL/font state is consistent), at
     *  vanilla's exact GUI scale. Called only while ChatOptimize is on and in a world. */
    public static void renderChat(int w, int h) {
        try {
            int sw, sh, sf;
            if (hudScaledW > 0) { // vanilla's real scale factor = display px / scaled px
                sw = hudScaledW; sh = hudScaledH;
                sf = Math.max(1, Math.round((float) w / hudScaledW));
            } else {              // fallback: MC auto-scale (guiScale = auto)
                sf = guiScaleFactor(w, h); sw = w / sf; sh = h / sf;
            }
            if (++chatCalls == 1) System.out.println("[IEA] chat scale: display=" + w + "x" + h
                    + " scaled=" + sw + "x" + sh + " sf=" + sf
                    + " hudScaled=" + hudScaledW + "x" + hudScaledH);
            // Feed the overlay the mouse in scaled (GUI) units + a fresh left-click edge, but
            // only while the chat input is open (that's when the cursor is free to click a button).
            boolean chatOpen = Mc.isChatOpen();
            float mx = Mouse.getX() / (float) sf;
            float my = (h - Mouse.getY()) / (float) sf;
            boolean down = chatOpen && Mouse.isButtonDown(0);
            boolean edge = down && !prevChatMouseDown;
            prevChatMouseDown = down;
            dev.iea.client.render.ChatOverlay.setPointer(mx, my, chatOpen, edge);

            GL11.glPushMatrix();
            GL11.glScalef(sf, sf, 1f); // draw in scaled (GUI) units like vanilla chat
            try { dev.iea.client.render.ChatOverlay.render(sw, sh); }
            finally { GL11.glPopMatrix(); }
        } catch (Throwable ignored) { }
    }
    private static boolean prevChatMouseDown = false; // rising-edge for the chat translate button

    // Minecraft's auto GUI scale (guiScale = auto). Fallback when the real value isn't cached.
    private static int guiScaleFactor(int w, int h) {
        int sf = 1;
        while (sf < 6 && w / (sf + 1) >= 320 && h / (sf + 1) >= 240) sf++;
        return sf;
    }

    /** Chat text width through the FontRenderer funnel (NO bypass) so it follows the IEAFont
     *  toggle and matches what chatDrawText renders. */
    public static int chatTextWidth(String s) {
        Object fr = frRef();
        if (fr == null || s == null) return 0;
        ensureFrMethods(fr);
        if (frWidthM == null) return 0;
        try { return ((Integer) frWidthM.invoke(fr, s)).intValue(); }
        catch (Throwable t) { return 0; }
    }

    /** Draw chat text through the FontRenderer funnel WITHOUT the bypass, so the IEAFont hook
     *  applies: IEA font when IEAFont is on, vanilla when off — both with § colours. */
    public static void chatDrawText(String s, float x, float y, int color, boolean shadow) {
        Object fr = frRef();
        if (fr == null || s == null) return;
        ensureFrMethods(fr);
        if (frDrawM == null) return;
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            frDrawM.invoke(fr, s, Float.valueOf(x), Float.valueOf(y),
                    Integer.valueOf(color), Boolean.valueOf(shadow));
        } catch (Throwable ignored) { }
    }

    private static void vanillaDraw(Object fr, String s, float x, float y, int color, boolean shadow) {
        fontBypass = true;
        try {
            frDrawM.invoke(fr, s, Float.valueOf(x), Float.valueOf(y),
                    Integer.valueOf(color), Boolean.valueOf(shadow));
        } catch (Throwable ignored) { }
        finally { fontBypass = false; }
    }

    // Bridge that lets the client UI (Font) fall back to the vanilla FontRenderer when
    // the IEAFont module is OFF, so one toggle swaps the font for the GUI *and* the game.
    // the standard FontRenderer: cached from the first drawString the hook sees, or fetched
    // straight from Minecraft when nothing has drawn vanilla text yet (the IEA main menu)
    private static Object frRef() {
        if (vanillaFr == null) vanillaFr = Mc.fontRenderer();
        return vanillaFr;
    }

    // Draw a GUI label at VANILLA's font size in both font modes. It routes through the
    // vanilla FontRenderer's own drawString/getStringWidth WITHOUT the bypass flag, so the
    // IEAFont funnel hook applies: plain vanilla text when IEAFont is off, and the
    // vanilla-SIZED IEA font (mcFont) when it's on. Either way it matches vanilla's size,
    // unlike our larger UI fonts. cyCenter = the box centre (vanilla puts the top at -4).
    public static void drawVanillaLabel(String text, float cx, float cyCenter, int color) {
        Object fr = frRef();
        if (fr == null || text == null) return;
        ensureFrMethods(fr);
        if (frDrawM == null || frWidthM == null) return;
        try {
            int w = ((Integer) frWidthM.invoke(fr, text)).intValue(); // funnel-aware width
            float x = cx - w / 2f, y = cyCenter - 4f;
            int col = (color & 0xFC000000) == 0 ? (color | 0xFF000000) : color;
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            frDrawM.invoke(fr, text, Float.valueOf(x), Float.valueOf(y), Integer.valueOf(col), Boolean.FALSE);
        } catch (Throwable ignored) { }
    }

    public static final Font.Vanilla FONT_BRIDGE = new Font.Vanilla() {
        // read the LIVE IEAFont state (not the once-per-frame cached fontOn) so the GUI
        // font is correct on the very first main-menu frame, before onFrame has cached it
        public boolean active() {
            try { return inited && !Modules.on("IEAFont") && frRef() != null; }
            catch (Throwable t) { return false; }
        }
        public float rawWidth(String s) {
            Object fr = frRef();
            if (fr == null) return 0f;
            ensureFrMethods(fr);
            return (frWidthM == null) ? 0f : vanillaWidth(fr, s);
        }
        public void rawDraw(String s, float x, float y, int argb) {
            Object fr = frRef();
            if (fr == null) return;
            ensureFrMethods(fr);
            if (frDrawM != null) vanillaDraw(fr, s, x, y, argb, false);
        }
    };

    private static float mcFontYOff;
    private static void ensureMcFont() {
        if (mcFont != null) return;
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
        try {
            mcFont = new Font();
            mcFont.init(20, false);
        } finally {
            GL11.glPopAttrib();
        }
        // scale by the measured glyph-body height (Yu Gothic's line box has lots of
        // padding — scaling by full line height made the text look tiny). Vanilla
        // glyphs are ~8 px of the 9-unit line.
        mcFontScale = 8f / mcFont.getInkHeight();
        // vertically centre the 8px glyph body in the 9-unit vanilla line, matching where
        // vanilla draws (was biased ~0.5px low, which made IEA text sit below other text).
        float pad = (9f - mcFont.getInkHeight() * mcFontScale) / 2f; // ≈0.5px top padding
        // align the ink top to the vanilla draw y, then lift ~0.5px more: Yu Gothic's measured
        // ink sits a touch low, which showed most on nametags (text below the username box).
        mcFontYOff = (pad - 1.0f) / mcFontScale - mcFont.getInkTop();
    }

    private static Object vanillaFr; // cached standard FontRenderer (for the self-nametag draw)
    public static int fontDrawString(Object fr, String s, float x, float y, int color, boolean shadow) {
        try {
            if (vanillaFr == null && fr != null) vanillaFr = fr; // first one = the standard font
            if (!inited || !fontOn || fontBypass || s == null) return -1;
            ensureMcFont();
            if ((color & 0xFC000000) == 0) color |= 0xFF000000; // vanilla: 0 alpha -> opaque
            // when the caller left blend OFF, vanilla effectively ignores the alpha
            // bits (e.g. the scoreboard's 0x20FFFFFF renders opaque) — replicate that
            if (!GL11.glIsEnabled(GL11.GL_BLEND)) color |= 0xFF000000;

            java.util.List<Object[]> toks = fontTokens(s);
            boolean mixed = false;
            for (int i = 0; i < toks.size(); i++)
                if (((Boolean) toks.get(i)[2]).booleanValue()) { mixed = true; break; }
            if (mixed) {
                ensureFrMethods(fr);
                if (frDrawM == null || frWidthM == null) return -1;
            }

            // per-token x offsets in GUI units (vanilla-run widths from vanilla)
            float[] offs = new float[toks.size()];
            float gx = 0;
            for (int i = 0; i < toks.size(); i++) {
                Object[] t = toks.get(i);
                offs[i] = gx;
                gx += ((Boolean) t[2]).booleanValue()
                        ? vanillaWidth(fr, (String) t[0])
                        : mcFont.getWidthF((String) t[0]) * mcFontScale;
            }

            // vanilla-rendered runs (the recursive call manages its own state/shadow)
            for (int i = 0; i < toks.size(); i++) {
                Object[] t = toks.get(i);
                if (((Boolean) t[2]).booleanValue())
                    vanillaDraw(fr, (String) t[0], x + offs[i], y,
                            tokColor(color, ((Integer) t[1]).intValue(), false), shadow);
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                    | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
            GL11.glPushMatrix();
            float saveAlpha = Gl.alpha;
            try {
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                // the lightmap unit darkens world text (signs/nametags) — disable it
                // for the draw; popAttrib(GL_TEXTURE_BIT) restores it exactly
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glTranslatef(x, y, 0f);
                GL11.glScalef(mcFontScale, mcFontScale, 1f);
                Gl.alpha = 1f;
                float inv = 1f / mcFontScale;
                for (int pass = shadow ? 0 : 1; pass < 2; pass++) {
                    boolean sp = pass == 0;
                    for (int i = 0; i < toks.size(); i++) {
                        Object[] t = toks.get(i);
                        if (((Boolean) t[2]).booleanValue()) continue;
                        mcFont.draw((String) t[0], offs[i] * inv + (sp ? inv : 0f),
                                mcFontYOff + (sp ? inv : 0f),
                                tokColor(color, ((Integer) t[1]).intValue(), sp));
                    }
                }
            } finally {
                Gl.alpha = saveAlpha;
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
            return (int) (x + gx) + (shadow ? 1 : 0);
        } catch (Throwable t) { return -1; }
    }

    public static int fontWidth(Object fr, String s) {
        try {
            if (!inited || !fontOn || fontBypass || s == null) return -1;
            ensureMcFont();
            // same tokenisation as the draw side, so widths always match
            java.util.List<Object[]> toks = fontTokens(s);
            float gx = 0;
            for (int i = 0; i < toks.size(); i++) {
                Object[] t = toks.get(i);
                if (((Boolean) t[2]).booleanValue()) {
                    ensureFrMethods(fr);
                    if (frWidthM == null) return -1;
                    gx += vanillaWidth(fr, (String) t[0]);
                } else {
                    gx += mcFont.getWidthF((String) t[0]) * mcFontScale;
                }
            }
            return Math.round(gx);
        } catch (Throwable t) { return -1; }
    }

    public static int fontCharWidth(char c) {
        try {
            if (!inited || !fontOn || fontBypass || c == '§') return -1; // vanilla: -1 for §
            ensureMcFont();
            if (c != ' ' && !fontHasChar(c)) return -1;    // unsupported: vanilla width
            return Math.round(mcFont.getWidthF(String.valueOf(c)) * mcFontScale);
        } catch (Throwable t) { return -1; }
    }

    // Gui.drawTexturedModalRect filter: suppress the vanilla heart/hunger/armor 9x9
    // sprites from icons.png (texture rows v=0 hearts, v=9 armor, v=27 food) while the
    // stat-bars option replaces them. Air bubbles (v=18) are kept.
    private static long lastStatIconNs = 0;
    // set true only while we run a slider's vanilla mouseDragged (which both updates the
    // value AND draws the widgets.png knob) — so we keep the update but skip the vanilla
    // knob and paint our own themed one over the exact same spot.
    public static boolean suppressKnob = false;
    public static boolean filterStatIcon(int tx, int ty, int w, int h) {
        try {
            // vanilla slider knob halves: drawTexturedModalRect(x, y, 0|196, 66, 4, 20)
            if (suppressKnob && ty == 66 && w == 4 && h == 20) return true;
            if (w != 9 || h != 9) return false;
            if (ty != 0 && ty != 9 && ty != 27) return false;
            if (tx < 16 || tx > 169) return false;
            Module m = Modules.get("Hotbar");
            if (m == null || !m.enabled || !m.bool("bars")) return false;
            lastStatIconNs = System.nanoTime();
            return true;
        } catch (Throwable t) { return false; }
    }

    private static void drawStatBars(int sw, int sh) {
        float hp = Mc.health();
        int food = Mc.foodLevel();
        int armor = Mc.armorPoints();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Gl.alpha = 1f;
            // same footprint as the vanilla rows: 81 wide, 9 tall; hearts row at
            // sh-39 (left), food row right-aligned, armor row at sh-49
            float barW = 81, barH = 9;
            if (hp >= 0) statBar(sw / 2f - 91, sh - 39, barW, barH, hp / 20f, 0xFFE65050, fmtStat(hp));
            if (food >= 0) statBar(sw / 2f + 91 - barW, sh - 39, barW, barH, food / 20f, 0xFFE6A035,
                    String.valueOf(food));
            if (armor > 0) statBar(sw / 2f - 91, sh - 49, barW, barH, armor / 20f, 0xFFAEC2D5,
                    String.valueOf(armor));
        } finally {
            GL11.glPopAttrib();
        }
    }

    // 19.5 -> "19.5", 20.0 -> "20"
    private static String fmtStat(float v) {
        int i = Math.round(v);
        return (Math.abs(v - i) < 0.05f) ? String.valueOf(i) : String.format("%.1f", v);
    }

    // stat bar: full-height coloured fill on a dark base, IEA-style subtle corners,
    // no frame; the label is the raw 0-20 value, double-drawn for extra weight
    private static void statBar(float x, float y, float w, float h, float frac, int color, String s) {
        frac = frac < 0f ? 0f : (frac > 1f ? 1f : frac);
        float r = 2f; // small radius: corners stay visible even on a 9px-tall bar
        Gl.roundedRect(x, y, w, h, r, 0xD90E0F14);                      // dark base
        if (frac > 0f) Gl.roundedRect(x, y, Math.max(r * 2, w * frac), h, r, color);
        GL11.glPushMatrix();
        try {
            float k = (h >= 12) ? 0.55f : 0.45f; // our font is in raw px; shrink to GUI units
            GL11.glTranslatef(x + w / 2f, y + h / 2f, 0f);
            GL11.glScalef(k, k, 1f);
            if (rowFont != null) {
                rowFont.drawCentered(s, 0, 0, 0xFFFFFFFF);
                rowFont.drawCentered(s, 0.8f, 0, 0xFFFFFFFF); // thicken (extra bold)
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static float selX = -1f;
    private static long selNs = 0;

    // IEA-styled hotbar, replacing GuiIngame.renderHotbar when the Hotbar module is
    // on. Background/selection are raw (state-bracketed); the items are drawn with
    // the vanilla RenderItem (cache-routed, so it stays consistent) + GUI lighting.
    public static boolean drawHotbar(Object gui, Object sr, float partial) {
        try {
            // Cache the REAL scaled resolution every frame (before the module gate) so the chat
            // overlay can match vanilla's exact GUI scale instead of guessing.
            int[] whc = Mc.scaledSize(sr);
            if (whc != null && whc[0] > 0) { hudScaledW = whc[0]; hudScaledH = whc[1]; }
            if (!inited || !Modules.on("Hotbar")) return false;
            int[] wh = Mc.scaledSize(sr);
            if (wh == null) return false;
            int sw = wh[0], sh = wh[1];
            int sel = Mc.hotbarSelected();
            Object[] stacks = Mc.hotbarStacks();

            float bx = sw / 2f - 91, by = sh - 22;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                Gl.alpha = 1f;
                float r = 5f;
                Gl.roundedRect(bx, by, 182, 22, r, 0xE6121319);          // dark base
                Gl.roundedOutline(bx, by, 182, 22, r, 1f, Theme.BORDER);
                // faint dividers between the 9 slots so it reads as a hotbar
                for (int i = 1; i < 9; i++) Gl.rect(bx + 1 + i * 20, by + 5, 1f, 12, 0x14FFFFFF);
                // selected slot — eased toward the target when "smooth" is on
                Module hm = Modules.get("Hotbar");
                float target = sel * 20;
                long nsNow = System.nanoTime();
                float dt = (selNs == 0) ? 0f : (nsNow - selNs) / 1.0e9f;
                selNs = nsNow;
                if (selX < 0 || hm == null || !hm.bool("smooth")) selX = target;
                else {
                    float a = 1f - (float) Math.exp(-dt / 0.05f);
                    selX += (target - selX) * a;
                    if (Math.abs(target - selX) < 0.3f) selX = target;
                }
                float sx = bx + selX;
                Gl.roundedRect(sx, by, 22, 22, r, Theme.accentA(0x2E));
                Gl.roundedOutline(sx, by, 22, 22, r, 1.5f, Theme.ACCENT);
            } finally {
                GL11.glPopAttrib();
            }

            // health / hunger / armor as bars + percent (replaces the vanilla icons,
            // which filterStatIcon suppresses). Only while vanilla tried to draw them
            // recently (= survival mode; creative draws none).
            Module hm2 = Modules.get("Hotbar");
            if (hm2 != null && hm2.bool("bars")
                    && System.nanoTime() - lastStatIconNs < 1000000000L) {
                drawStatBars(sw, sh);
            }

            if (stacks != null) {
                // vanilla RenderHelper (cache-routed) — its disable also turns OFF the
                // GL lighting that the item-overlay draw re-enables; without it the rest
                // of the overlay (hearts/armor) rendered lit-with-no-lights = black
                boolean rh = Mc.guiItemLightingOn();
                if (!rh) HudManager.itemLightingOn(); // raw fallback
                try {
                    for (int i = 0; i < stacks.length; i++) {
                        if (stacks[i] == null) continue;
                        Mc.renderHotbarItem(stacks[i], sw / 2 - 90 + i * 20 + 2, sh - 19);
                    }
                } finally {
                    if (rh) Mc.guiItemLightingOff();
                    else {
                        HudManager.itemLightingOff();
                        GL11.glDisable(GL11.GL_LIGHTING); // overlay re-enables it; force off
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // IEA main-menu background: cobblestone fill + IEA logo. Only invoked when IEAGui is
    // on (the transformer guards on Hook.ieaGuiOn()); the buttons are drawn afterwards by
    // the super.drawScreen call in the bytecode. When IEAGui is off the transformer runs
    // the original vanilla drawScreen instead (panorama + Minecraft logo + stock buttons).
    private static Cobble cobble;
    public static void drawMainMenu(Object screen, int mx, int my, float partial) {
        try {
            int w = Mc.guiW(screen), h = Mc.guiH(screen);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL13.GL_MULTISAMPLE); // avoid double-AA on the first (MSAA) frames
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                Gl.alpha = 1f;
                // ALWAYS lay down an opaque full-screen cover first — a GUI-only screen isn't
                // guaranteed a per-frame colour clear, so if this is skipped the buttons draw
                // over stale frames and their semi-transparent borders bloom (thick/blurry).
                // Oversized so it covers regardless of the (possibly still-unknown) scaled size.
                Gl.rect(-16, -16, 100000, 100000, Theme.DEEP);
                // launcher backdrop grid on top (only once the scaled size is known)
                if (w > 0 && h > 0) Gl.grid(0, 0, w, h, 24, Theme.GRID);
            } finally {
                GL11.glPopAttrib();
            }
            // Footer wordmark: drawn AFTER the attrib block through the vanilla text path
            // (drawVanillaLabel), exactly like the restyled buttons. Drawing our own IEA-font
            // quads here would leave the font atlas bound and desync GlStateManager, garbling
            // the button labels that super.drawScreen paints next.
            if (w > 0 && h > 0)
                drawVanillaLabel("Minecraft 1.8.9   ·   IEA CLIENT", w / 2f, h - 12f, Theme.MUTED);
        } catch (Throwable ignored) { }
    }

    // Paints the button in the ClickGui style (dark rounded base + green border + our
    // font). Only invoked when IEAGui is on (the transformer guards on Hook.ieaGuiOn());
    // when off the transformer runs the original vanilla drawButton instead. We still call
    // the button's mouseDragged (which vanilla's drawButton does) so sliders keep their
    // knob AND their drag logic; for a plain button mouseDragged is a no-op.
    public static void drawButton(Object btn, Object mc, int mx, int my) {
        try {
            if (!Mc.btnReady(btn) || !Mc.btnVisible(btn)) return;
            int x = Mc.btnX(btn), y = Mc.btnY(btn), w = Mc.btnW(btn), h = Mc.btnH(btn);
            if (w <= 0 || h <= 0) return;
            boolean enabled = Mc.btnEnabled(btn);
            boolean hov = mx >= x && my >= y && mx < x + w && my < y + h;
            Mc.btnSetHovered(btn, hov);
            String txt = Mc.btnText(btn);

            // base + border (raw GL, wrapped so GlStateManager's cache stays consistent;
            // finally guarantees the pop even if a draw throws)
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE); // world render leaves cull ON -> would cull our fill
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                // Our shapes already carry their own 1px AA. On the first title-screen frames
                // the game renders to a driver-multisampled framebuffer (ms=true), which
                // double-AAs our outline into a thick blurry "glow"; disable MSAA + polygon/line
                // smoothing for our draw so the border stays a crisp 1.2px line.
                GL11.glDisable(GL13.GL_MULTISAMPLE);
                GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                Gl.alpha = 1f;
                float r = 6f;
                // match the calm ClickGui/HUD theme: neutral dark base, faint neutral
                // hairline, and only a subtle green edge on hover (no permanent bright green)
                int fill = !enabled ? 0xFF0E0F14 : (hov ? Theme.PANEL2 : Theme.ROW);
                int border = (enabled && hov) ? ((0x99 << 24) | (Theme.ACCENT & 0xFFFFFF)) : Theme.BORDER;
                Gl.roundedRect(x, y, w, h, r, fill);
                Gl.roundedOutline(x, y, w, h, r, 1.2f, border);
            } finally {
                GL11.glPopAttrib();
            }

            // slider knob + drag (vanilla mouseDragged; uses GlStateManager, so call it
            // OUTSIDE our push/pop). No-op for plain buttons. This also updates the value
            // while dragging; we suppress its widgets.png knob (suppressKnob -> filterStatIcon)
            // and paint our own themed knob over the exact same spot just below.
            suppressKnob = true;
            Mc.callMouseDragged(btn, mc, mx, my);
            suppressKnob = false;

            float sliderVal = Mc.btnSliderValue(btn); // >= 0 only for sliders (e.g. volume/FOV)
            if (sliderVal >= 0f) {
                GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                try {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    Gl.alpha = 1f;
                    float kc = x + sliderVal * (w - 8) + 4, kw = 8; // vanilla knob footprint
                    int knob = !enabled ? Theme.MUTED : (hov ? Theme.ACCENT : Theme.ACCENT2);
                    Gl.roundedRect(kc - kw / 2f, y + 3, kw, h - 6, 3f, knob);
                    Gl.roundedOutline(kc - kw / 2f, y + 3, kw, h - 6, 3f, 1f, Theme.accentA(0x99));
                } finally {
                    GL11.glPopAttrib();
                }
            }

            // NB: push only ENABLE/COLOR bits, NOT GL_TEXTURE_BIT. The vanilla FontRenderer
            // (IEAFont off) binds the font texture via GlStateManager; if glPopAttrib
            // restored the texture BINDING here, GlStateManager's cache would still say
            // "font bound" while the actual binding reverted — so the NEXT button's
            // bindTexture(font) is skipped and its label draws with the wrong texture
            // (only the first button looked right). Leaving the binding alone keeps them in sync.
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                int col = !enabled ? Theme.MUTED : (hov ? Theme.ACCENT : Theme.TEXT);
                // draw the label at vanilla's font size in BOTH modes (vanilla text when
                // IEAFont is off, vanilla-sized IEA font when on) so buttons match vanilla
                drawVanillaLabel(txt, x + w / 2f, y + h / 2f, col);
            } finally {
                GL11.glPopAttrib();
            }
        } catch (Throwable ignored) { }
    }

    // IEAGui module gates the restyled vanilla GUI (title menu + buttons). When off, the
    // transformer falls through to the ORIGINAL vanilla method body, so the panorama,
    // Minecraft logo and stock buttons all return exactly as vanilla. Default ON; before
    // Modules.init() (first main-menu frame) treat as ON so nothing flickers.
    // Called from injected bytecode in Transformer (GuiMainMenu / GuiButton) — keep public.
    public static boolean ieaGuiOn() {
        try { return Modules.ALL.isEmpty() || Modules.on("IEAGui"); }
        catch (Throwable t) { return true; }
    }

    // CustomSky is a BUILT-IN feature (no toggle): the renderSky hook OVERLAYS the vanilla
    // sky when the selected resource pack provides an MCPatcher custom sky — exactly like
    // OptiFine/Lunar (no pack sky -> vanilla sky). We inject at renderSky's exit so vanilla
    // draws its day/night gradient + sun/moon first, then our layers fade/rotate on top;
    // that (not a static opaque box) is what makes day and night actually change.
    // renderSky = bfr.a(FI)V. All loading/rendering is clean-room (dev.iea.client.render.Sky).
    public static boolean customSkyOn() {
        try { return Sky.isReady(); } catch (Throwable t) { return false; }
    }

    public static void drawCustomSky(float partial, int pass) {
        try {
            Sky.maybeReload();               // pick up in-game resource-pack switches
            if (Sky.isReady()) Sky.render(partial);
        } catch (Throwable ignored) { }
    }

    // Dynamic FPS (the "DynamicFps" client setting): while the game window is unfocused
    // (ALT+TAB / other apps), cap the loop to ~30 fps to cut GPU/CPU load and heat. Zero
    // effect while playing.
    private static final long UNFOCUSED_FRAME_MS = 33L;

    // Per-module toggle keybinds: edge-detected key -> flip that module's enabled flag.
    private static final java.util.HashMap<String, Boolean> bindPrev = new java.util.HashMap<String, Boolean>();
    private static void updateBinds() {
        // ignore while the menu is open (binding a key there) or while typing in chat
        boolean block = isGuiOpen() || Mc.isChatOpen();
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module m = Modules.ALL.get(i);
            int k = m.toggleKey;
            if (k <= 0) { if (!bindPrev.isEmpty()) bindPrev.remove(m.name); continue; }
            boolean down = !block && Keyboard.isKeyDown(k);
            Boolean prev = bindPrev.get(m.name);
            if (down && (prev == null || !prev.booleanValue())) m.enabled = !m.enabled;
            bindPrev.put(m.name, Boolean.valueOf(down));
        }
    }

    public static void onFrame() {
        try {
            Mc.observeView(); // identify thirdPersonView + RenderManager pitch field over time
            if (inited && Modules.on("DynamicFps") && !Display.isActive()) {
                try { Thread.sleep(UNFOCUSED_FRAME_MS); } catch (InterruptedException ignored) { }
            }
            if (!inited) {
                Modules.init();
                hud = new HudManager();
                gui = new ClickGui(hud);
                // texture uploads change the binding — restore it so the vanilla
                // state cache stays consistent from the very first frame
                GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
                try {
                    rowFont = new Font(); rowFont.init(14, true); // bold (+slightly larger) small text
                    titleFont = new Font(); titleFont.init(16, true);
                    bigFont = new Font(); bigFont.init(24, true);
                    hudFont = new Font(); hudFont.init(16, false);
                    logo = new Logo(); logo.init(128);
                } finally {
                    GL11.glPopAttrib();
                }
                Font.vanilla = FONT_BRIDGE; // GUI follows the IEAFont toggle too
                Config.load(hud);
                inited = true;
                System.out.println("[IEA] client initialized");
            }

            // rebrand the game window (title + taskbar icon) to IEA — once, after the
            // display exists. Minecraft sets these only at startup, so this sticks.
            dev.iea.client.render.WindowChrome.applyOnce();


            // apply the user's theme accent colour (or revert to the stock lime when off)
            Module themeM = Modules.get("Theme");
            if (themeM != null && themeM.enabled) {
                int r = clampByte(themeM.num("r", 163)), g = clampByte(themeM.num("g", 230)),
                    b = clampByte(themeM.num("b", 53));
                Theme.setAccent((r << 16) | (g << 8) | b);
            } else {
                Theme.setAccent(Theme.DEFAULT_ACCENT);
            }

            // cache IEAFont for the hot font hooks — BEFORE the splash early-return so it's
            // current even on splash/resource-reload frames (kept the initial menu on the
            // vanilla font otherwise)
            fontOn = Modules.on("IEAFont");

            // startup: paint the IEA logo over the Mojang splash screen
            if (splashActive) { splashActive = false; drawSplash(); return; }

            irDepth = 0; // self-heal the ItemRenderer hook depth each frame
            mvX *= 0.80f; mvY *= 0.80f; // decay the mouse-movement indicator

            Mc.tryDiscover(); // locate Minecraft / GameSettings (mapping bootstrap)
            applyGameFeatures();
            updateBinds();      // per-module toggle keybinds
            updateSwingSpeed(); // SwingSpeed: drive the swing counter faster
            DeathAlert.tick();  // Bedwars: poll chat for teammate deaths

            // F6: dump the render object graph (run while standing in a world,
            // looking at a block, near a TNT/entity) -> iea-render-dump.txt
            boolean dumpKey = Keyboard.isKeyDown(Keyboard.KEY_F6);
            if (dumpKey && !prevDump) Mc.dumpRenderInfo();
            prevDump = dumpKey;

            int w = Display.getWidth();
            int h = Display.getHeight();

            long now = System.currentTimeMillis();
            frames++;
            if (now - lastFpsTime >= 1000) { fps = frames; frames = 0; lastFpsTime = now; }

            // clicks are counted in Hook.filterEvent from the mouse event queue (not polled
            // here), so CPS is not capped by the frame rate
            boolean l = Mouse.isButtonDown(0);
            updateOldAnimSwing(l, now); // OldAnimations: per-click swing cycle
            updateSneakSmooth();        // OldAnimations sneak ease + zoom ease

            // Zoom: hold the key to ease toward the factor (render-side, no settings)
            Module zm = Modules.get("Zoom");
            int zk = (zm != null) ? zm.keyCode("key", Keyboard.KEY_C) : 0;
            zoomTarget = (zm != null && zm.enabled && !isGuiOpen() && zk > 0 && Keyboard.isKeyDown(zk))
                    ? Math.max(1f, zm.num("zoom", 4f)) : 1f;
            prune(leftClicks, now);
            prune(rightClicks, now);

            boolean wasOpen = gui.isOpen();
            boolean binding = gui.isBinding();
            boolean tog = Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            if (tog && !prevToggle && !binding) gui.onToggleKey();
            prevToggle = tog;
            boolean esc = Keyboard.isKeyDown(Keyboard.KEY_ESCAPE);
            if (esc && !prevEsc && !binding) gui.onEscape();
            prevEsc = esc;
            boolean open = gui.isOpen();

            // side-mouse-button hotbar handling (inventory swap / slot select) — see below
            handleSideHotbar(open);

            // opening: release all key bindings (vanilla-GUI parity) — otherwise a
            // key/side-button held at open sticks pressed (its release gets drained)
            if (open && !wasOpen) Mc.unpressAllKeys();

            if (wasOpen) {
                // Flush the input queues so events don't leak to the game, and pull
                // the menu's scroll straight from the mouse wheel events.
                draining = true;
                int guard = 0;
                while (Keyboard.next() && guard++ < 512) { // swallow keys; feed the search box
                    if (Keyboard.getEventKeyState())
                        gui.onCharTyped(Keyboard.getEventCharacter(), Keyboard.getEventKey());
                }
                guard = 0;
                while (Mouse.next() && guard++ < 512) scrollAccum += Mouse.getEventDWheel();
                draining = false;
            }
            // Grab handling (edge-triggered). On close, only re-grab when no vanilla
            // screen (inventory/chat/...) is open underneath — re-grabbing over an open
            // GUI was what made the cursor vanish (the GUI still needs the cursor).
            if (open) { if (Mouse.isGrabbed()) Mouse.setGrabbed(false); } // show OS cursor
            else if (wasOpen && !Mc.isVanillaScreenOpen()) Mouse.setGrabbed(true);

            // keep the GUI supplied with HUD data so the layout editor can show
            // the real elements (not just outlines)
            gui.setHudData(hudFont, logo, fps, leftClicks.size(), rightClicks.size());

            // Only draw while actually in-game (mouse grabbed) or when our menu is
            // open. Hides the HUD on the pause/main menu and when not in a world.
            boolean inGame = Mouse.isGrabbed();
            // Motion blur over the world — done BEFORE our HUD so the HUD stays crisp.
            if (inGame) {
                Module mbm = Modules.get("MotionBlur");
                if (mbm != null && mbm.enabled) MotionBlur.render(w, h, mbm.num("amount", 0.5f));
                Module satm = Modules.get("Saturation");
                if (satm != null && satm.enabled) Saturation.render(w, h, satm.num("amount", 1f));
            }
            // ChatOptimize: we suppress vanilla drawChat and render our own here, in the same
            // pass the HUD/GUI text draws in — so it draws whenever in a world, including while
            // a vanilla screen (chat input / inventory) is open, matching vanilla chat.
            // Learn the chat-screen class: arm on the chat key (T / "/") with nothing open, then
            // snapshot whatever screen appears next. Lets us tell the chat input from inventory.
            boolean chatKey = Keyboard.isKeyDown(Keyboard.KEY_T) || Keyboard.isKeyDown(Keyboard.KEY_SLASH);
            if (chatKey && !prevChatKey && !gui.isOpen() && !Mc.isVanillaScreenOpen()) chatKeyArmed = true;
            prevChatKey = chatKey;
            if (chatKeyArmed && Mc.isVanillaScreenOpen()) { Mc.captureChatScreen(); chatKeyArmed = false; }

            // Render our chat in-game (no screen) or while the chat input is open — but NOT under
            // inventory/other screens, so it doesn't draw on top of them (vanilla chat sits behind).
            boolean chatOpen = Mc.isChatOpen();
            boolean chatOn = chatOverlayOn() && Mc.localPlayer() != null && (Mouse.isGrabbed() || chatOpen);
            if (!chatOpen && prevVScreen) dev.iea.client.render.ChatOverlay.resetScroll(); // snap to newest on close
            prevVScreen = chatOpen;
            if (inGame || open || chatOn) {
                int scroll = scrollAccum; scrollAccum = 0;
                // Surface.end MUST run even if a renderer throws — a skipped pop
                // leaks the attrib/matrix stacks and corrupts everything afterwards
                Surface.begin(w, h);
                try {
                    if (chatOn) renderChat(w, h); // behind the HUD
                    if (inGame) hud.render(hudFont, logo, fps, leftClicks.size(), rightClicks.size());
                    if (inGame) DeathAlert.render(bigFont, w, h); // teammate-death title over the HUD
                    if (open) {
                        gui.render(rowFont, titleFont, bigFont, logo,
                                Mouse.getX(), h - Mouse.getY(), Mouse.isButtonDown(0), scroll);
                    }
                } finally {
                    Surface.end(w, h);
                }
                // vanilla-rendered icons (RenderItem / TextureManager) must run OUTSIDE
                // the raw-state pass so GlStateManager's cache stays consistent
                hud.flushDeferredIcons();
                // Reset to the world's standard render state. Vanilla's renderItemIntoGUI
                // (HUD armor/potion icons) ends with GL_BLEND ENABLED and GL_ALPHA_TEST
                // DISABLED; the next world frame doesn't re-set these before terrain, so a
                // leftover "blend on / alpha off" makes leaves & other cutout blocks render
                // see-through. syncBlendAlpha sets blend off / alpha on AND writes the
                // GlStateManager cache to match, so vanilla's own enableBlend for the block
                // overlay / translucents next frame isn't skipped (which left it opaque).
                Mc.syncBlendAlpha(false, true);
            } else {
                scrollAccum = 0;
            }
        } catch (Throwable t) {
            // never crash the game from our hook
        }
    }

    // In the inventory, hovering a slot and pressing a hotbar key swaps that item to the
    // slot — but vanilla wires this to KEYBOARD keys only, so a side mouse button bound to
    // a hotbar slot does nothing there. Bridge it: while a vanilla screen is open, forward
    // the side-button press to the screen's keyTyped using the button's key code
    // (MC convention: button index - 100). Vanilla then runs its normal swap (+ packet),
    // so it respects the user's binding and works in multiplayer.
    // Make the side mouse buttons act like their bound hotbar key in every state:
    //  - a vanilla screen open (inventory): forward to keyTyped so the hover-to-hotbar
    //    SWAP runs (vanilla wires that to keyboard keys only);
    //  - in-game / while holding Tab: select the bound hotbar slot directly. Vanilla
    //    already does this in plain play (our set is the same value = harmless), but it
    //    mis-handles it in some states (both buttons ending on one slot); reading each
    //    button's own bound slot and running after the game tick corrects that.
    // keyCode for a mouse button = index - 100 (MC convention).
    private static boolean prevSide3, prevSide4;
    private static int forcedSlot = -1;     // hotbar slot to hold right after a side-button press
    private static long forcedUntil = 0;    // until when (ms) to keep forcing it
    private static void handleSideHotbar(boolean ourMenuOpen) {
        try {
            boolean b3 = Mouse.isButtonDown(3);
            boolean b4 = Mouse.isButtonDown(4);
            if (ourMenuOpen) { prevSide3 = b3; prevSide4 = b4; forcedSlot = -1; return; }
            boolean screen = Mc.isVanillaScreenOpen();
            long now = System.currentTimeMillis();
            if (b3 && !prevSide3) onSideButton(3 - 100, screen, now);
            if (b4 && !prevSide4) onSideButton(4 - 100, screen, now);
            // hold the chosen slot briefly so vanilla's mis-dispatch can't override it
            // (only outside screens; in a screen we run vanilla's own swap instead)
            if (!screen && forcedSlot >= 0) {
                if (now < forcedUntil) Mc.setHotbarSlot(forcedSlot);
                else forcedSlot = -1;
            }
            prevSide3 = b3; prevSide4 = b4;
        } catch (Throwable ignored) { }
    }

    private static void onSideButton(int keyCode, boolean screen, long now) {
        if (screen) { Mc.sendKeyToScreen(keyCode); return; } // inventory: vanilla hover-to-hotbar swap
        int slot = Mc.slotForKey(keyCode);                   // gameplay / player-list: switch held slot
        if (slot >= 0) { forcedSlot = slot; forcedUntil = now + 160; Mc.setHotbarSlot(slot); }
    }

    // --- HitColor: re-tint the hurt overlay that RendererLivingEntity.setBrightness set.
    // The damage flash colour is NOT the primary glColor — setBrightness fills its
    // brightnessBuffer with (1,0,0,0.3) and binds it as the lightmap unit's GL_TEXTURE_ENV_COLOR
    // (GL_CONSTANT), which the combiner interpolates toward. So we re-issue that env colour with
    // ours on the lightmap unit (GL_TEXTURE1), keeping the 0.3 strength, then restore unit 0
    // (which is where setBrightness left the active unit, so GlStateManager's cache stays valid).
    private static final FloatBuffer HITCOL = BufferUtils.createFloatBuffer(4);
    private static boolean hitColorLogged = false;
    public static void onSetBrightness(boolean applied, Object entity) {
        try {
            if (!applied) return;
            Module m = Modules.get("HitColor");
            if (m == null || !m.enabled) return;
            if (!Mc.isHurt(entity)) return;
            if (!hitColorLogged) { hitColorLogged = true; System.out.println("[IEA] HitColor applied to a hurt entity"); }
            HITCOL.clear();
            HITCOL.put(m.num("red", 85) / 255f).put(m.num("green", 140) / 255f)
                    .put(m.num("blue", 255) / 255f).put(0.3f);
            HITCOL.flip();
            // the tint constant (GL_TEXTURE_ENV_COLOR) lives on one of the two combiner
            // units; set it on BOTH, then leave the active unit at 0 (where setBrightness
            // left it) so GlStateManager's cache stays valid.
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, HITCOL);
            HITCOL.rewind();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, HITCOL);
        } catch (Throwable ignored) { }
    }

    // --- SwingSpeed: speed up the local player's swing animation by advancing the swing
    // counter faster (field-driven; no bytecode hook). Vanilla bumps swingProgressInt once
    // per tick over getArmSwingAnimationEnd (~6) ticks; we add (speed-1) extra on each tick
    // it changes, so the swing finishes in ~1/speed the time. Vanilla still owns the reset,
    // so this self-heals and never sticks.
    private static int swingPrevInt = -999;
    private static void updateSwingSpeed() {
        try {
            Module ss = Modules.get("SwingSpeed");
            if (ss == null || !ss.enabled || !Mc.swingFieldsReady()) { swingPrevInt = -999; return; }
            int speed = (int) ss.num("speed", 2f);
            if (speed <= 1 || !Mc.isSwinging()) { swingPrevInt = -999; return; }
            int cur = Mc.swingInt();
            if (cur != swingPrevInt) {            // vanilla ticked it -> add our extra
                int boosted = cur + (speed - 1);
                Mc.setSwingInt(boosted);
                swingPrevInt = boosted;
            }
        } catch (Throwable ignored) { swingPrevInt = -999; }
    }

    // Fullbright (gamma) + Zoom (fov) via the discovered GameSettings fields.
    private static void applyGameFeatures() {
        if (!Mc.ready()) return;

        // Find the ItemRenderer up-front while OldAnimations is on, so we can confirm
        // the obf class even if the (F)V draw hook hasn't fired yet (logs once).
        if (Modules.on("OldAnimations")) Mc.ensureItemRenderer();

        // NoHurtCam: keep hurtTime at 0 so the damage camera-shake never plays
        if (Modules.on("NoHurtCam")) Mc.zeroHurtTime();

        boolean fb = Modules.on("Fullbright");
        if (fb) {
            if (!fullbrightOn) { savedGamma = Math.min(Mc.getSettingFloat(GAMMA, 0.5f), 1f); fullbrightOn = true; }
            Mc.setSettingFloat(GAMMA, 1000f);
        } else if (fullbrightOn) {
            Mc.setSettingFloat(GAMMA, savedGamma);
            fullbrightOn = false;
        }

        // ToggleSprint: a real toggle — press the key once to latch sprint on, press
        // again to turn it off (default Left Ctrl, the vanilla sprint key). While latched
        // we hold the sprint keybind so the player keeps sprinting; released when the menu
        // is open, the module is off, or the toggle is off.
        Module tsm = Modules.get("ToggleSprint");
        boolean tsEnabled = tsm != null && tsm.enabled;
        if (tsEnabled) {
            int key = tsm.keyCode("key", org.lwjgl.input.Keyboard.KEY_LCONTROL);
            boolean keyDown = key > 0 && !isGuiOpen() && org.lwjgl.input.Keyboard.isKeyDown(key);
            if (keyDown && !prevSprintKey) sprintToggled = !sprintToggled; // edge: flip on press
            prevSprintKey = keyDown;
        } else {
            sprintToggled = false;
            prevSprintKey = false;
        }

        boolean ts = sprintToggled && Mc.sprintReady() && !isGuiOpen();
        if (ts) {
            Mc.setSprint(true);
            sprintForced = true;
        } else if (sprintForced) {
            Mc.setSprint(false);
            sprintForced = false;
        }
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // Block overlay: called from inside RenderGlobal.drawSelectionBoundingBox(AABB),
    // where the box is already offset to camera space. We draw a translucent colored
    // box, then restore the vanilla outline colour so the original lines are unaffected.
    public static void onSelectionBox(Object aabb) {
        try {
            Module m = Modules.get("BlockOverlay");
            if (m == null || !m.enabled) return;
            double[] b = Mc.aabb(aabb);
            if (b == null) return;
            float r = m.num("red", 163) / 255f;
            float g = m.num("green", 230) / 255f;
            float bl = m.num("blue", 53) / 255f;
            float a = m.num("alpha", 110) / 255f;
            GL11.glColor4f(r, g, bl, a);
            drawFilledBox(b[0], b[1], b[2], b[3], b[4], b[5]);
            GL11.glColor4f(0f, 0f, 0f, 0.4f); // vanilla selection-outline colour
        } catch (Throwable ignored) { }
    }

    // Hitbox: called from RenderManager.doRenderEntity entry. (x,y,z) is the entity's
    // interpolated camera-relative position; we size the box from the entity's own
    // bounding box (dimensions only) so no fragile position fields are needed.
    // SoundTuner: called from SoundManager.getNormalizedVolume exit. Scales the volume of
    // specific sounds by a per-sound multiplier (0 = mute .. up; >1 boosts quiet/distant
    // sounds toward the engine's cap). Unlisted sounds pass through unchanged.
    private static final String[][] SOUND_KEYS = {
            { "explode", "random.explode" }, { "eat", "random.eat" }, { "drink", "random.drink" },
            { "levelup", "random.levelup" }, { "orb", "random.orb" }, { "pop", "random.pop" },
            { "bow", "random.bow" }, { "hurt", "game.player.hurt" }, { "fire", "fire.ignite" },
            { "portal", "mob.endermen.portal" }, { "anvil", "random.anvil_land" }, { "click", "random.click" },
    };
    public static float filterSoundVolume(float vol, Object isound) {
        try {
            Module m = Modules.get("SoundTuner");
            if (m == null || !m.enabled) return vol;
            String name = Mc.soundName(isound);
            if (name == null) return vol;
            for (String[] s : SOUND_KEYS)
                if (s[1].equals(name)) {
                    float f = m.num(s[0], 1f);
                    return f == 1f ? vol : Math.max(0f, vol * f);
                }
            return vol;
        } catch (Throwable t) { return vol; }
    }

    // LevelHead: vanilla never renders your OWN nametag (canRenderName returns false for the
    // local player). Force it on so vanilla itself renders it; the front-view pitch flip is
    // corrected by filterNameTag/afterNameTag.
    public static boolean filterRenderName(boolean show, Object entity) {
        try {
            if (show) return true;
            Module lh = Modules.get("LevelHead");
            if (lh != null && lh.enabled && lh.bool("self") && Mc.isLocalPlayer(entity) && Mc.onHypixel())
                return true;
        } catch (Throwable ignored) { }
        return show;
    }

    // LevelHead: called from Render.renderLivingLabel entry to prepend a level tag to the
    // vanilla nametag string (so it's drawn as part of the nametag, background and all).
    // per-type nametag prefix: colour code + trailing symbol. Index = LevelHead value kind.
    private static final String[] LVL_COLOR = { "§b", "§6", "§b", "§a", "§e" };
    private static final String[] LVL_SUFFIX = { "", "✫", "⋆", "", "W" };
    private static boolean nametagPitchFlipped = false;
    public static String filterNameTag(Object entity, String name) {
        try {
            Module lh = Modules.get("LevelHead");
            if (lh == null || !lh.enabled || name == null || !Mc.onHypixel()) return name;
            boolean self = Mc.isLocalPlayer(entity);
            if (self) {
                if (!lh.bool("self")) return name; // own nametag disabled
                // FIX the front-view inversion in vanilla's own render: negate the camera pitch
                // it billboards with, just for this label; afterNameTag() restores it.
                if (Mc.thirdPersonView() == 2 && Mc.negateNametagPitch()) nametagPitchFlipped = true;
            }
            Object prof = Mc.playerProfile(entity);
            String uuid = (prof != null) ? Mc.profileId(prof) : null;
            String me = (prof != null) ? Mc.profileName(prof) : null;
            if (uuid == null || me == null || me.length() < 3) return name; // not a player
            // Only the line that actually holds the username gets the level — a player's nametag
            // can be several lines (rank line, name line, …) and each comes through here.
            if (Mc.stripFormatting(name).toLowerCase().indexOf(me.toLowerCase()) < 0) return name;
            int type = (int) lh.num("type", 0);
            String val = LevelHead.value(uuid, type);
            if (val == null) return name; // pending / none -> leave the name as-is
            String c = (type >= 0 && type < LVL_COLOR.length) ? LVL_COLOR[type] : "§b";
            String sfx = (type >= 0 && type < LVL_SUFFIX.length) ? LVL_SUFFIX[type] : "";
            return c + "[" + val + sfx + "] §r" + name;
        } catch (Throwable t) { return name; }
    }

    // Called at renderLivingLabel exit: undo the self-nametag pitch negation done at entry.
    public static void afterNameTag() {
        try {
            if (nametagPitchFlipped) { Mc.negateNametagPitch(); nametagPitchFlipped = false; }
        } catch (Throwable ignored) { }
    }

    public static void onEntityRender(Object entity, double x, double y, double z) {
        try {
            Module tnt = Modules.get("TNTTimer");
            if (tnt != null && tnt.enabled) {
                int fuse = Mc.tntFuse(entity);
                if (fuse >= 0) {
                    // Reuse Minecraft's own nametag renderer (Render.renderLivingLabel) so the
                    // timer is visually identical to a nametag. It adds the entity height + 0.5
                    // itself, so pass the raw render x/y/z. Colour via a § code: green -> yellow
                    // -> red as the fuse runs down (20 ticks = 1s, primed at 80).
                    String c = fuse > 50 ? "§a" : (fuse > 25 ? "§e" : "§c");
                    Mc.renderNameLabel(entity, c + String.format("%.1f", Math.max(0, fuse) / 20.0f), x, y, z);
                }
            }
            // LevelHead: all nametags (incl. your own) are drawn by vanilla; filterNameTag adds
            // the level and fixes the front-view pitch flip for the local player.
            // ItemPhysics (full): freeze the item's spin/bob (zero its motion fields) and lay
            // it flat by rotating 90° about its own position. Pop + restore at the exit hook.
            // Safety net: if a previous frame's restore was skipped (rare), undo it now.
            if (ipFrozen != null) { Mc.restoreItemMotion(ipFrozen, ipSaved); ipFrozen = null; ipSaved = null; }
            Module ip = Modules.get("ItemPhysics");
            if (ip != null && ip.enabled && "Item".equals(Mc.entityTypeName(entity))) {
                ipSaved = Mc.saveAndZeroItemMotion(entity);
                ipFrozen = entity;
                // Lay the item flat by rotating 90° about X. A floating item can't be centred on
                // both axes with one pivot, so we expose two: `height` = the flat item's vertical
                // place (default = hitbox centre), `depth` = how far it slides along the flip axis
                // (default cancels vanilla's ~0.1 hover). Tune these to sit it in the hitbox.
                double[] ib = Mc.aabb(Mc.entityBox(entity));
                double boxC = (ib != null) ? (ib[4] - ib[1]) / 2.0 : 0.125;
                double height = boxC + ip.num("height", 0f);
                double depth = ip.num("depth", 0.1f);
                GL11.glPushMatrix();
                GL11.glTranslated(x, y + height, z);
                GL11.glRotatef(90f, 1f, 0f, 0f);
                GL11.glTranslated(-x, -y - depth, -z);
                itemPhysicsPushed = true;
            }
        } catch (Throwable ignored) { }
    }

    // ItemPhysics render-pass state: the matrix we pushed, and the item whose motion fields
    // we zeroed (with their saved values) so the exit hook can restore them.
    private static boolean itemPhysicsPushed = false;
    private static Object ipFrozen = null;
    private static float[] ipSaved = null;

    // Hitbox: called at the EXIT of doRenderEntity (after the entity's own model is drawn),
    // so the box layers on top of the model. Depth test stays on, so walls still occlude it.
    public static void onEntityHitbox(Object entity, double x, double y, double z) {
        try {
            // undo the ItemPhysics rotation + restore the item's motion fields first, so the
            // hitbox draws in camera space and the entity keeps ticking normally
            if (itemPhysicsPushed) {
                GL11.glPopMatrix();
                itemPhysicsPushed = false;
                if (ipFrozen != null) { Mc.restoreItemMotion(ipFrozen, ipSaved); ipFrozen = null; ipSaved = null; }
            }
            Module hb = Modules.get("Hitbox");
            if (hb != null && hb.enabled) drawHitbox(hb, entity, x, y, z);
        } catch (Throwable ignored) { }
    }

    // Hitbox: draw the entity's REAL (vanilla) bounding box — same data the F3+B debug box
    // uses, read straight off the entity — but coloured per category and with an optional
    // eye-line. (x,y,z) is the interpolated render origin (feet centre) from doRenderEntity.
    private static void drawHitbox(Module m, Object entity, double x, double y, double z) {
        if (Mc.isLocalPlayer(entity)) return;   // never box yourself
        if (Mc.isInvisible(entity)) return;     // fairness: skip invisible entities
        double[] b = Mc.aabb(Mc.entityBox(entity));
        if (b == null) return;
        // Vanilla F3+B draws the REAL AABB offset by the entity's position (no assumption
        // that it is centred on / starts at the position). Offsetting by entity.pos makes it
        // correct for off-centre boxes too (item frames, dropped items, hanging entities).
        double[] p = Mc.entityPos(entity);
        double x0, y0, z0, x1, y1, z1;
        if (p != null) {
            x0 = b[0] - p[0]; y0 = b[1] - p[1]; z0 = b[2] - p[2];
            x1 = b[3] - p[0]; y1 = b[4] - p[1]; z1 = b[5] - p[2];
        } else { // fallback: assume centred horizontally, bottom at the position
            double w = (b[3] - b[0]) / 2.0, d = (b[5] - b[2]) / 2.0;
            x0 = -w; y0 = 0; z0 = -d; x1 = w; y1 = b[4] - b[1]; z1 = d;
        }
        if (x1 - x0 <= 0 && y1 - y0 <= 0 && z1 - z0 <= 0) return;

        int rgb = catColor(m, category(entity));
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, bl = (rgb & 0xFF) / 255f;
        float a = m.num("alpha", 160) / 255f;
        float lw = m.num("width", 2f);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_DEPTH_TEST);    // occluded by walls, like vanilla F3+B
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);  // smoothing clamps width to ~1px and washes out
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glLineWidth(lw);
            GL11.glColor4f(r, g, bl, a);
            drawBoxOutline(x0, y0, z0, x1, y1, z1);
            if (m.bool("eyeline") && Mc.isLiving(entity)) { // only meaningful for players/mobs
                double[] lk = Mc.lookVec(entity);
                if (lk != null) {
                    double ey = y0 + (y1 - y0) * 0.85; // ~eye height; vanilla draws this line red
                    GL11.glColor4f(1f, 0f, 0f, a);
                    GL11.glBegin(GL11.GL_LINES);
                    GL11.glVertex3d(0, ey, 0);
                    GL11.glVertex3d(lk[0] * 2, ey + lk[1] * 2, lk[2] * 2);
                    GL11.glEnd();
                }
            }
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } finally {
            GL11.glDepthMask(true);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    // Category: 0 enemy, 1 teammate, 2 mob/animal, 3 arrow, 4 ender pearl, 5 other
    // projectile, 6 other. Players split by DeathAlert's teammate set; everything else is
    // classified from Minecraft's own (de-obfuscated) EntityList name.
    private static int category(Object entity) {
        Object prof = Mc.playerProfile(entity);
        if (prof != null) {
            // enemy/teammate split relies on Hypixel party + scoreboard; off Hypixel it's
            // unreliable, so only distinguish teammates there — elsewhere all players = enemy.
            return (Mc.onHypixel() && DeathAlert.isMate(Mc.profileName(prof))) ? 1 : 0;
        }
        String t = Mc.entityTypeName(entity);
        if ("Arrow".equals(t)) return 3;
        if ("ThrownEnderpearl".equals(t)) return 4;
        if (isThrown(t)) return 5;
        if (Mc.isLiving(entity)) return 2;
        return 6;
    }

    private static boolean isThrown(String t) {
        if (t == null) return false;
        return t.equals("Snowball") || t.equals("ThrownEgg") || t.equals("ThrownExpBottle")
                || t.equals("ThrownPotion") || t.equals("Fireball") || t.equals("SmallFireball")
                || t.equals("WitherSkull") || t.equals("FishHook") || t.equals("DragonFireball");
    }

    private static final String[] CAT_KEY = { "en", "te", "mo", "ar", "pe", "pr", "ot" };
    private static final int[] CAT_DEF = {
            0xFF3C3C, 0x3CFF5A, 0xFFD23C, 0xE6E6E6, 0xBE5AFF, 0x5ADCFF, 0xAAAAAA };
    private static int catColor(Module m, int cat) {
        if (cat < 0 || cat >= CAT_KEY.length) cat = 6;
        String p = CAT_KEY[cat];
        int def = CAT_DEF[cat];
        int r = (int) m.num(p + "_r", (def >> 16) & 0xFF);
        int g = (int) m.num(p + "_g", (def >> 8) & 0xFF);
        int b = (int) m.num(p + "_b", def & 0xFF);
        return (r << 16) | (g << 8) | b;
    }

    private static void drawBoxOutline(double x0, double y0, double z0, double x1, double y1, double z1) {
        GL11.glBegin(GL11.GL_LINES);
        // bottom rectangle
        edge(x0, y0, z0, x1, y0, z0); edge(x1, y0, z0, x1, y0, z1);
        edge(x1, y0, z1, x0, y0, z1); edge(x0, y0, z1, x0, y0, z0);
        // top rectangle
        edge(x0, y1, z0, x1, y1, z0); edge(x1, y1, z0, x1, y1, z1);
        edge(x1, y1, z1, x0, y1, z1); edge(x0, y1, z1, x0, y1, z0);
        // verticals
        edge(x0, y0, z0, x0, y1, z0); edge(x1, y0, z0, x1, y1, z0);
        edge(x1, y0, z1, x1, y1, z1); edge(x0, y0, z1, x0, y1, z1);
        GL11.glEnd();
    }

    private static void edge(double ax, double ay, double az, double bx, double by, double bz) {
        GL11.glVertex3d(ax, ay, az); GL11.glVertex3d(bx, by, bz);
    }

    private static void drawFilledBox(double x0, double y0, double z0, double x1, double y1, double z1) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x0, y0, z0); GL11.glVertex3d(x1, y0, z0); GL11.glVertex3d(x1, y0, z1); GL11.glVertex3d(x0, y0, z1); // bottom
        GL11.glVertex3d(x0, y1, z0); GL11.glVertex3d(x0, y1, z1); GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x1, y1, z0); // top
        GL11.glVertex3d(x0, y0, z0); GL11.glVertex3d(x0, y1, z0); GL11.glVertex3d(x1, y1, z0); GL11.glVertex3d(x1, y0, z0); // north
        GL11.glVertex3d(x0, y0, z1); GL11.glVertex3d(x1, y0, z1); GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x0, y1, z1); // south
        GL11.glVertex3d(x0, y0, z0); GL11.glVertex3d(x0, y0, z1); GL11.glVertex3d(x0, y1, z1); GL11.glVertex3d(x0, y1, z0); // west
        GL11.glVertex3d(x1, y0, z0); GL11.glVertex3d(x1, y1, z0); GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x1, y0, z1); // east
        GL11.glEnd();
    }

    private static void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
    }
}
