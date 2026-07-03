package dev.iea.client.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import dev.iea.client.Hook;
import dev.iea.client.Mc;
import dev.iea.client.Theme;
import dev.iea.client.module.Module;
import dev.iea.client.module.Modules;
import dev.iea.client.render.Font;
import dev.iea.client.render.Gl;
import dev.iea.client.render.Logo;

/** Renders the HUD (framed) and a drag-to-move, edge-anchored layout editor. */
public final class HudManager {
    public final List<HudElement> elements = new ArrayList<HudElement>();
    private HudElement dragging;
    private float dragOffX, dragOffY;
    private final long startTime = System.currentTimeMillis();

    private static final String[] NAMES =
            { "Watermark", "FPS", "CPS", "Clock", "Memory", "ArrayList", "SessionTimer",
              "Keystrokes", "MouseStrokes", "Crosshair",
              "Reach", "Coordinates", "Ping", "PotionHUD", "ArmorStatus",
              "ComboCounter", "ServerAddress" };

    public HudManager() { resetPositions(); }

    private static HudElement makeDefault(String n) {
        int L = HudElement.LEFT, R = HudElement.RIGHT, T = HudElement.TOP, B = HudElement.BOTTOM;
        if (n.equals("Watermark")) return new HudElement(n, L, T, 10, 10);
        if (n.equals("FPS")) return new HudElement(n, L, T, 12, 54);
        if (n.equals("CPS")) return new HudElement(n, L, T, 12, 82);
        if (n.equals("Memory")) return new HudElement(n, L, T, 12, 110);
        if (n.equals("Clock")) return new HudElement(n, R, T, 12, 12);
        if (n.equals("ArrayList")) return new HudElement(n, R, T, 12, 44);
        if (n.equals("SessionTimer")) return new HudElement(n, R, B, 12, 12);
        if (n.equals("Keystrokes")) return new HudElement(n, L, B, 24, 24);
        if (n.equals("MouseStrokes")) return new HudElement(n, L, B, 24, 116);
        if (n.equals("Crosshair")) return new HudElement(n, HudElement.CENTERX, HudElement.CENTERY, 0, 0);
        if (n.equals("Reach")) return new HudElement(n, HudElement.CENTERX, HudElement.CENTERY, 0, 60);
        if (n.equals("Coordinates")) return new HudElement(n, HudElement.CENTERX, B, 0, 12);
        if (n.equals("Ping")) return new HudElement(n, L, T, 12, 138);
        if (n.equals("PotionHUD")) return new HudElement(n, R, B, 12, 60);
        if (n.equals("ArmorStatus")) return new HudElement(n, R, HudElement.CENTERY, 12, 0);
        if (n.equals("ComboCounter")) return new HudElement(n, HudElement.CENTERX, HudElement.CENTERY, 0, 90);
        if (n.equals("ServerAddress")) return new HudElement(n, L, T, 12, 166);
        return new HudElement(n, L, T, 10, 10);
    }

    public void resetPositions() {
        elements.clear();
        for (String n : NAMES) elements.add(makeDefault(n));
    }

    public boolean has(String name) {
        for (int i = 0; i < elements.size(); i++) if (elements.get(i).name.equals(name)) return true;
        return false;
    }

    public void resetOne(String name) {
        HudElement d = makeDefault(name);
        for (int i = 0; i < elements.size(); i++) {
            HudElement e = elements.get(i);
            if (e.name.equals(name)) { e.ax = d.ax; e.ay = d.ay; e.mx = d.mx; e.my = d.my; }
        }
    }

    // resolve screen rect from anchor + margin + size
    private void layout(HudElement e, int w, int h) {
        // the crosshair is always pinned to the exact screen centre (not movable)
        if (e.name.equals("Crosshair")) {
            e.ax = HudElement.CENTERX; e.ay = HudElement.CENTERY; e.mx = 0; e.my = 0;
        }
        // ArmorStatus: the "grow" setting picks which edge stays pinned when the item
        // count changes — the anchored edge is the fixed one, so normalize the anchor
        // on the growth axis (converting the margin keeps the on-screen position).
        if (e.name.equals("ArmorStatus")) {
            Module m = Modules.get(e.name);
            if (m != null) {
                boolean flip = m.bool("grow");
                if (m.bool("horizontal")) {
                    int want = flip ? HudElement.RIGHT : HudElement.LEFT; // RIGHT pinned = grows left
                    if (e.ax != want) {
                        e.mx = (want == HudElement.RIGHT) ? w - (e.x + e.w) : e.x;
                        e.ax = want;
                    }
                } else {
                    int want = flip ? HudElement.BOTTOM : HudElement.TOP; // BOTTOM pinned = grows up
                    if (e.ay != want) {
                        e.my = (want == HudElement.BOTTOM) ? h - (e.y + e.h) : e.y;
                        e.ay = want;
                    }
                }
            }
        }
        if (e.ax == HudElement.LEFT) e.x = e.mx;
        else if (e.ax == HudElement.RIGHT) e.x = w - e.w - e.mx;
        else e.x = (w - e.w) / 2f + e.mx;
        if (e.ay == HudElement.TOP) e.y = e.my;
        else if (e.ay == HudElement.BOTTOM) e.y = h - e.h - e.my;
        else e.y = (h - e.h) / 2f + e.my;
        e.x = clamp(e.x, 0, Math.max(0, w - e.w));
        e.y = clamp(e.y, 0, Math.max(0, h - e.h));
    }

    // subtle panel for elements that need a backdrop (icons/pads): dark fill + a faint
    // neutral hairline — no bright green border, so the HUD stays calm and simple
    private void card(float x, float y, float w, float h) {
        Gl.roundedRect(x, y, w, h, 5, 0x99000000);
        Gl.roundedOutline(x, y, w, h, 5, 1, 0x26FFFFFF);
    }

    // ---- normal HUD render ----
    public void render(Font font, Logo logo, int fps, int cpsL, int cpsR) {
        int w = Display.getWidth(), h = Display.getHeight();
        for (int i = 0; i < elements.size(); i++) {
            HudElement e = elements.get(i);
            if (!Modules.on(e.name)) continue;
            Module m = Modules.get(e.name);
            float scale = m.num("scale", 1f);
            Gl.alpha = m.num("opacity", 100) / 100f;

            layout(e, w, h);
            // scale the element about its anchor point (e.x, e.y)
            GL11.glPushMatrix();
            GL11.glTranslatef(e.x, e.y, 0);
            GL11.glScalef(scale, scale, 1);
            GL11.glTranslatef(-e.x, -e.y, 0);
            draw(e, font, logo, fps, cpsL, cpsR);
            GL11.glPopMatrix();

            Gl.alpha = 1f;
            e.w *= scale; e.h *= scale; // effective size for layout / hit-test / outline
        }
    }

    private void draw(HudElement e, Font font, Logo logo, int fps, int cpsL, int cpsR) {
        Module m = Modules.get(e.name);
        if (e.name.equals("Watermark")) {
            float bs = 32; e.w = bs; e.h = bs; // overall size handled by the scale setting
            logo.draw(e.x, e.y, bs);
        } else if (e.name.equals("FPS")) {
            text(e, font, m.bool("label") ? "FPS " + fps : String.valueOf(fps), Theme.TEXT);
        } else if (e.name.equals("CPS")) {
            text(e, font, m.bool("split") ? "CPS " + cpsL + " / " + cpsR : "CPS " + cpsL, Theme.TEXT);
        } else if (e.name.equals("Clock")) {
            text(e, font, clockText(m.bool("h24"), m.bool("sec")), Theme.TEXT);
        } else if (e.name.equals("Memory")) {
            text(e, font, memoryText(m.bool("percent")), Theme.TEXT);
        } else if (e.name.equals("SessionTimer")) {
            text(e, font, sessionText(), Theme.TEXT);
        } else if (e.name.equals("ArrayList")) {
            drawArrayList(e, font, m.bool("bg"));
        } else if (e.name.equals("Keystrokes")) {
            drawKeystrokes(e, font, m.bool("space"), m.bool("mouse"));
        } else if (e.name.equals("MouseStrokes")) {
            drawMouseStrokes(e, font);
        } else if (e.name.equals("Crosshair")) {
            drawCrosshair(e, (int) m.num("type", 0), m.num("size", 1f));
        } else if (e.name.equals("Reach")) {
            float r = Hook.lastReach;
            text(e, font, r < 0 ? "Reach -.--" : String.format("Reach %.2f m", r), Theme.TEXT);
        } else if (e.name.equals("Coordinates")) {
            text(e, font, coordsText(), Theme.TEXT);
        } else if (e.name.equals("Ping")) {
            int ms = Mc.pingMs();
            text(e, font, ms < 0 ? "Ping --" : "Ping " + ms + " ms", Theme.TEXT);
        } else if (e.name.equals("PotionHUD")) {
            drawPotions(e, font);
        } else if (e.name.equals("ArmorStatus")) {
            drawArmor(e, font);
        } else if (e.name.equals("ComboCounter")) {
            int c = Hook.combo();
            text(e, font, c + (c == 1 ? " Hit" : " Hits"), c > 0 ? Theme.TEXT : Theme.MUTED);
        } else if (e.name.equals("ServerAddress")) {
            String addr = Mc.serverAddress();
            text(e, font, addr == null ? "Singleplayer" : addr, Theme.TEXT);
        }
    }

    // Mouse MOVEMENT indicator: a pad whose dot reflects recent mouse motion and
    // springs back to centre. (Clicks are shown by Keystrokes' "mouse" option instead.)
    private void drawMouseStrokes(HudElement e, Font font) {
        float box = 56; e.w = box; e.h = box;
        card(e.x, e.y, box, box);
        float cx = e.x + box / 2f, cy = e.y + box / 2f;
        float lim = box / 2f - 7;
        float dx = clamp(Hook.moveX() * 0.5f, -lim, lim);
        float dy = clamp(Hook.moveY() * 0.5f, -lim, lim);
        // faint centre guides
        Gl.rect(cx - 0.5f, e.y + 5, 1, box - 10, Theme.accentA(0x33));
        Gl.rect(e.x + 5, cy - 0.5f, box - 10, 1, Theme.accentA(0x33));
        // moving dot + a line from centre
        Gl.rect(cx, cy, dx, 1.5f, Theme.accentA(0x66));
        Gl.rect(cx, cy, 1.5f, dy, Theme.accentA(0x66));
        Gl.roundedRect(cx + dx - 4, cy + dy - 4, 8, 8, 4, Theme.ACCENT);
    }

    private void drawCrosshair(HudElement e, int type, float k) {
        float size = 18 * k; e.w = size; e.h = size;
        float cx = e.x + size / 2f, cy = e.y + size / 2f;
        int col = 0xFFFFFFFF;
        // inverted-colour blend like the vanilla crosshair (adapts to the background)
        GL11.glBlendFunc(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE_MINUS_SRC_COLOR);
        float th = Math.max(1.5f, 2f * k), len = 7f * k, gap = 3f * k;
        if (type == 1) {                       // cross with a centre gap
            Gl.rect(cx - len, cy - th / 2f, len - gap, th, col);
            Gl.rect(cx + gap, cy - th / 2f, len - gap, th, col);
            Gl.rect(cx - th / 2f, cy - len, th, len - gap, col);
            Gl.rect(cx - th / 2f, cy + gap, th, len - gap, col);
        } else if (type == 2) {                // dot only
            float d = Math.max(2f, 3f * k);
            Gl.rect(cx - d / 2f, cy - d / 2f, d, d, col);
        } else if (type == 3) {                // T-shape (no top arm)
            Gl.rect(cx - len, cy - th / 2f, len * 2, th, col);
            Gl.rect(cx - th / 2f, cy, th, len, col);
        } else if (type == 4) {                // circle (+ centre dot)
            Gl.roundedOutline(cx - len, cy - len, len * 2, len * 2, len, th, col);
            float d = Math.max(2f, 2.5f * k);
            Gl.rect(cx - d / 2f, cy - d / 2f, d, d, col);
        } else if (type == 5) {                // square outline
            Gl.rect(cx - len, cy - len, len * 2, th, col);
            Gl.rect(cx - len, cy + len - th, len * 2, th, col);
            Gl.rect(cx - len, cy - len, th, len * 2, col);
            Gl.rect(cx + len - th, cy - len, th, len * 2, col);
        } else if (type == 6) {                // X (diagonal cross)
            line(cx - len, cy - len, cx + len, cy + len, th, col);
            line(cx - len, cy + len, cx + len, cy - len, th, col);
        } else if (type == 7) {                // arrow (chevron pointing up)
            line(cx - len, cy + len * 0.55f, cx, cy - len * 0.45f, th, col);
            line(cx + len, cy + len * 0.55f, cx, cy - len * 0.45f, th, col);
        } else {                               // 0: plain cross
            Gl.rect(cx - len, cy - th / 2f, len * 2, th, col);
            Gl.rect(cx - th / 2f, cy - len, th, len * 2, col);
        }
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void line(float x1, float y1, float x2, float y2, float w, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        float a = ((argb >>> 24) & 0xFF) / 255f * Gl.alpha;
        GL11.glColor4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, a);
        GL11.glLineWidth(w);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    // always-on readouts (FPS/CPS/Ping/…) in a calm subtle panel, all one text colour
    private void text(HudElement e, Font font, String s, int color) {
        float pad = 6;
        float bw = font.getWidth(s) + pad * 2;
        float bh = font.getHeight() + pad * 2;
        card(e.x, e.y, bw, bh);
        font.draw(s, e.x + pad, e.y + pad, color);
        e.w = bw; e.h = bh;
    }

    private static final int KEY_IDLE = 0xB0262B36; // semi-transparent key background (calm, unobtrusive)

    private void drawArrayList(HudElement e, Font font, boolean bg) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module m = Modules.ALL.get(i);
            if (m.enabled) names.add(m.name);
        }
        final Font f = font;
        Collections.sort(names, new Comparator<String>() {
            public int compare(String a, String b) { return Integer.compare(f.getWidth(b), f.getWidth(a)); }
        });
        float maxW = 0;
        for (int i = 0; i < names.size(); i++) maxW = Math.max(maxW, font.getWidth(names.get(i)) + 16);
        float y = e.y;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            float bw = font.getWidth(n) + 16, bh = font.getHeight() + 6;
            float bx = (e.ax == HudElement.RIGHT) ? e.x + maxW - bw : e.x;
            if (bg) card(bx, y, bw, bh);
            font.draw(n, bx + 8, y + 3, Theme.TEXT);
            y += bh + 3;
        }
        e.w = Math.max(1, maxW); e.h = Math.max(1, y - e.y - 3);
    }

    private void drawKeystrokes(HudElement e, Font font, boolean showSpace, boolean showMouse) {
        float s = 26, g = 4;
        float w = s * 3 + g * 2;
        float h = 2 * s + g;
        if (showSpace) h += g + 14;
        if (showMouse) h += g + 18;
        // no surrounding panel — just the key tiles float on a transparent background
        float ox = e.x, oy = e.y;
        key(font, "W", ox + s + g, oy, s, Keyboard.isKeyDown(Keyboard.KEY_W));
        key(font, "A", ox, oy + s + g, s, Keyboard.isKeyDown(Keyboard.KEY_A));
        key(font, "S", ox + s + g, oy + s + g, s, Keyboard.isKeyDown(Keyboard.KEY_S));
        key(font, "D", ox + (s + g) * 2, oy + s + g, s, Keyboard.isKeyDown(Keyboard.KEY_D));
        float ry = oy + (s + g) * 2;
        if (showSpace) {
            boolean space = Keyboard.isKeyDown(Keyboard.KEY_SPACE);
            Gl.roundedRect(ox, ry, w, 14, 4, space ? Theme.ACCENT2 : KEY_IDLE);
            ry += 14 + g;
        }
        if (showMouse) {
            float half = (w - g) / 2f;
            boolean lb = Mouse.isButtonDown(0), rb = Mouse.isButtonDown(1);
            Gl.roundedRect(ox, ry, half, 18, 4, lb ? Theme.ACCENT2 : KEY_IDLE);
            font.drawCentered("L", ox + half / 2f, ry + 9, lb ? Theme.DARK : Theme.TEXT);
            Gl.roundedRect(ox + half + g, ry, half, 18, 4, rb ? Theme.ACCENT2 : KEY_IDLE);
            font.drawCentered("R", ox + half + g + half / 2f, ry + 9, rb ? Theme.DARK : Theme.TEXT);
        }
        e.w = w; e.h = h;
    }

    private void key(Font font, String c, float x, float y, float s, boolean down) {
        Gl.roundedRect(x, y, s, s, 6, down ? Theme.ACCENT2 : KEY_IDLE);
        font.drawCentered(c, x + s / 2f, y + s / 2f, down ? Theme.DARK : Theme.TEXT);
    }

    // ---- layout editor (drag + re-anchor to nearest corner) ----
    public void editDrag(int mx, int my, boolean down, boolean clicked) {
        if (clicked) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                HudElement e = elements.get(i);
                if (e.name.equals("Crosshair")) continue; // crosshair is fixed at centre
                if (Modules.on(e.name) && inside(mx, my, e.x - 4, e.y - 4, e.w + 8, e.h + 8)) {
                    dragging = e; dragOffX = mx - e.x; dragOffY = my - e.y; break;
                }
            }
        }
        if (!down) dragging = null;
        if (dragging != null) {
            int w = Display.getWidth(), h = Display.getHeight();
            float absX = clamp(mx - dragOffX, 0, w - dragging.w);
            float absY = clamp(my - dragOffY, 0, h - dragging.h);
            if (absX + dragging.w / 2 < w / 2f) { dragging.ax = HudElement.LEFT; dragging.mx = absX; }
            else { dragging.ax = HudElement.RIGHT; dragging.mx = w - absX - dragging.w; }
            if (absY + dragging.h / 2 < h / 2f) { dragging.ay = HudElement.TOP; dragging.my = absY; }
            else { dragging.ay = HudElement.BOTTOM; dragging.my = h - absY - dragging.h; }
            dragging.x = absX; dragging.y = absY;
        }
    }

    public void drawOutlines(Font font) {
        for (int i = 0; i < elements.size(); i++) {
            HudElement e = elements.get(i);
            if (!Modules.on(e.name) || e.name.equals("Crosshair")) continue; // crosshair not movable
            Gl.roundedRect(e.x - 4, e.y - 4, e.w + 8, e.h + 8, 7, Theme.accentA(0x22));
            int b = Theme.ACCENT;
            Gl.rect(e.x - 4, e.y - 4, e.w + 8, 2, b);
            Gl.rect(e.x - 4, e.y + e.h + 2, e.w + 8, 2, b);
            Gl.rect(e.x - 4, e.y - 4, 2, e.h + 8, b);
            Gl.rect(e.x + e.w + 2, e.y - 4, 2, e.h + 8, b);
        }
    }

    private static String coordsText() {
        double[] pos = Mc.playerPos();
        if (pos == null) return "X 0  Y 0  Z 0";
        String dir = facing(Mc.playerYaw());
        return "X " + (int) Math.floor(pos[0]) + "  Y " + (int) Math.floor(pos[1])
                + "  Z " + (int) Math.floor(pos[2]) + (dir.isEmpty() ? "" : "  " + dir);
    }

    // MC yaw: 0 = south, increasing clockwise
    private static String facing(float yaw) {
        if (Float.isNaN(yaw)) return "";
        int d = (int) Math.floor(((yaw % 360 + 360) % 360 + 22.5f) / 45f) & 7;
        return new String[] { "S", "SW", "W", "NW", "N", "NE", "E", "SE" }[d];
    }

    private static final String[] POTION_NAMES = {
        "", "Speed", "Slowness", "Haste", "Mining Fatigue", "Strength", "Instant Health",
        "Instant Damage", "Jump Boost", "Nausea", "Regeneration", "Resistance",
        "Fire Resistance", "Water Breathing", "Invisibility", "Blindness", "Night Vision",
        "Hunger", "Weakness", "Poison", "Wither", "Health Boost", "Absorption", "Saturation" };

    private static String potionName(int id) {
        return (id > 0 && id < POTION_NAMES.length) ? POTION_NAMES[id] : ("Effect " + id);
    }

    private static String roman(int amp) {
        String[] r = { "", " II", " III", " IV", " V" };
        return (amp >= 0 && amp < r.length) ? r[amp] : " " + (amp + 1);
    }

    // status-icon index inside inventory.png per potion id (1.8.9 constants); -1 = none
    private static final int[] POTION_ICONS = {
        -1, 0, 1, 2, 3, 4, -1, -1, 10, 11, 7, 14, 15, 16, 8, 13, 12, 9, 5, 6, 17, 23, 18, -1 };

    private void drawPotions(HudElement e, Font font) {
        List<int[]> fx = Mc.potions();
        if (fx.isEmpty()) { e.w = 110; e.h = font.getHeight() + 16; return; } // outline only in edit
        float pad = 6, icon = 18, lh = icon + 4;
        List<String> lines = new ArrayList<String>();
        float maxW = 0;
        for (int i = 0; i < fx.size(); i++) {
            int[] f = fx.get(i);
            int sec = Math.max(0, f[1] / 20);
            String line = potionName(f[0]) + roman(f[2]) + "  " + (sec / 60) + ":" + String.format("%02d", sec % 60);
            lines.add(line);
            maxW = Math.max(maxW, font.getWidth(line));
        }
        float bw = pad + icon + 6 + maxW + pad;
        float bh = fx.size() * lh + pad * 2 - 4;
        card(e.x, e.y, bw, bh);
        float y = e.y + pad;
        for (int i = 0; i < fx.size(); i++) {
            int id = fx.get(i)[0];
            int idx = (id >= 0 && id < POTION_ICONS.length) ? POTION_ICONS[id] : -1;
            if (idx >= 0) potionIcon(idx, e.x + pad, y, icon);
            font.draw(lines.get(i), e.x + pad + icon + 6, y + (icon - font.getHeight()) / 2f, Theme.TEXT);
            y += lh;
        }
        e.w = bw; e.h = bh;
    }

    // one 18x18 status icon from inventory.png (icons start at v=198, 8 per row).
    // Binds the RAW texture id (never the vanilla TextureManager) so the in-pass GL
    // state stays consistent with GlStateManager's cache; the id is captured once in
    // the deferred stage.
    private void potionIcon(int idx, float x, float y, float size) {
        int tex = Mc.inventoryTexId();
        if (tex <= 0) { Mc.requestInventoryTexture(); return; }
        float u = (idx % 8) * 18, v = 198 + (idx / 8) * 18;
        float u0 = u / 256f, v0 = v / 256f, u1 = (u + 18) / 256f, v1 = (v + 18) / 256f;
        // save the currently-bound texture and restore it after: our raw bind here
        // otherwise leaves GlStateManager's cache out of sync, so the vanilla FontRenderer
        // (when IEAFont is off) skips its own bind and draws the numbers with THIS texture
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glColor4f(1f, 1f, 1f, Gl.alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x, y + size);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x + size, y + size);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x + size, y);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(x, y);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    // rows of {stack, int[]{slot, remaining, max, count}}: real item icons + the
    // remaining durability as a number (coloured by how much is left). Icons are
    // queued for the deferred stage (vanilla RenderItem must run OUTSIDE our raw
    // push/pop-attrib pass or GlStateManager's cache desyncs and corrupts rendering).
    private void drawArmor(HudElement e, Font font) {
        List<Object[]> rows = Mc.armorItems();
        if (rows.isEmpty()) { e.w = 96; e.h = font.getHeight() + 16; return; } // outline only in edit
        Module m = Modules.get(e.name);
        float scale = m != null ? m.num("scale", 1f) : 1f;
        boolean horiz = m != null && m.bool("horizontal");
        float pad = 6, icon = 18;
        if (horiz) {
            // icon on top, durability number centred BELOW it
            float fh = font.getHeight();
            float[] cellW = new float[rows.size()];
            float bw = pad;
            for (int i = 0; i < rows.size(); i++) {
                String t = armorValue((int[]) rows.get(i)[1]);
                cellW[i] = Math.max(icon, t.length() > 0 ? font.getWidth(t) : 0);
                bw += cellW[i] + (i < rows.size() - 1 ? 8 : 0);
            }
            bw += pad;
            float bh = pad * 2 + icon + 2 + fh;
            card(e.x, e.y, bw, bh);
            float x = e.x + pad, y = e.y + pad;
            for (int i = 0; i < rows.size(); i++) {
                int[] d = (int[]) rows.get(i)[1];
                String t = armorValue(d);
                queueIcon(rows.get(i)[0], e, x + (cellW[i] - icon) / 2f, y, icon, scale);
                if (t.length() > 0)
                    font.drawCentered(t, x + cellW[i] / 2f, y + icon + 2 + fh / 2f, armorColor(d));
                x += cellW[i] + 8;
            }
            e.w = bw; e.h = bh;
        } else {
            float lh = icon + 4;
            float txtW = 0;
            for (int i = 0; i < rows.size(); i++)
                txtW = Math.max(txtW, font.getWidth(armorValue((int[]) rows.get(i)[1])));
            float bw = pad + icon + 8 + txtW + pad;
            float bh = rows.size() * lh + pad * 2 - 4;
            card(e.x, e.y, bw, bh);
            float y = e.y + pad;
            for (int i = 0; i < rows.size(); i++) {
                int[] d = (int[]) rows.get(i)[1];
                String t = armorValue(d);
                if (t.length() > 0)
                    font.draw(t, e.x + pad + icon + 8, y + (icon - font.getHeight()) / 2f, armorColor(d));
                queueIcon(rows.get(i)[0], e, e.x + pad, y, icon, scale);
                y += lh;
            }
            e.w = bw; e.h = bh;
        }
    }

    private static int armorColor(int[] d) {
        if (d[1] >= 0 && d[2] > 0) {
            int pct = d[1] * 100 / d[2];
            return pct > 50 ? Theme.ACCENT : (pct > 25 ? 0xFFE6C235 : 0xFFE65050);
        }
        return Theme.TEXT;
    }

    // map local coords through the element's scale-about-(e.x,e.y) transform to screen
    private void queueIcon(Object stack, HudElement e, float lx, float ly, float icon, float scale) {
        pendingIcons.add(new Object[] { stack,
                Float.valueOf(e.x + (lx - e.x) * scale), Float.valueOf(e.y + (ly - e.y) * scale),
                Float.valueOf(icon * scale) });
    }

    private static String armorValue(int[] d) {
        if (d[1] >= 0) return String.valueOf(d[1]); // remaining durability
        return d[3] > 1 ? "x" + d[3] : "";          // no durability: stack count
    }

    // Deferred item icons: vanilla RenderItem routes its state through GlStateManager,
    // so it MUST run outside our raw push/pop-attrib pass (restoring raw state behind
    // the cache's back is what corrupted the sky/blocks). This stage runs after
    // Surface.end (actual state == cache state), uses only raw MATRIX ops + glClear
    // (neither is cached), and restores nothing else.
    private final List<Object[]> pendingIcons = new ArrayList<Object[]>();

    public void flushDeferredIcons() {
        if (Mc.inventoryTexturePending()) Mc.captureInventoryTexture();
        if (pendingIcons.isEmpty()) return;
        int w = Display.getWidth(), h = Display.getHeight();
        // net-zero raw state tweaks: read actual, set what icons need, restore exactly —
        // the GlStateManager cache never notices (fixes occasionally-black icons when the
        // frame ends with texturing off / fog on / depth writes off)
        boolean hadTex = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean hadMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean hadFog = GL11.glIsEnabled(GL11.GL_FOG);
        if (!hadTex) GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (!hadMask) GL11.glDepthMask(true);
        if (hadFog) GL11.glDisable(GL11.GL_FOG);
        // a still-enabled lightmap texture unit multiplies the icons dark
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        boolean hadTex1 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        if (hadTex1) GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        boolean rh = Mc.guiItemLightingOn(); // vanilla RenderHelper when available
        if (!rh) itemLightingOn();           // raw replica fallback
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, 1000, 3000); // vanilla GUI depth range
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); // stale depth must not occlude the icons
            for (int i = 0; i < pendingIcons.size(); i++) {
                Object[] ic = pendingIcons.get(i);
                GL11.glLoadIdentity();
                GL11.glTranslatef(((Float) ic[1]).floatValue(), ((Float) ic[2]).floatValue(), -2000f);
                float s = ((Float) ic[3]).floatValue() / 16f;
                GL11.glScalef(s, s, 1f);
                Mc.renderItemIcon(ic[0]);
            }
        } finally {
            pendingIcons.clear();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            if (rh) Mc.guiItemLightingOff(); else itemLightingOff();
            if (hadTex1) {
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            if (hadFog) GL11.glEnable(GL11.GL_FOG);
            if (!hadTex) GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (!hadMask) GL11.glDepthMask(false);
        }
    }

    // RenderItem ENABLES GL lighting for gui3d (block) models and expects the caller
    // to have configured RenderHelper's GUI standard item lighting — with no lights
    // set up, 3D blocks render pitch black. These bracket that setup with an exact
    // raw save/restore (net-zero for the GlStateManager cache).
    private static final boolean[] LSAVE = new boolean[3];
    private static int savedShade;
    private static final float[] savedAmb = new float[4];

    public static void itemLightingOn() {
        LSAVE[0] = GL11.glIsEnabled(GL11.GL_LIGHT0);
        LSAVE[1] = GL11.glIsEnabled(GL11.GL_LIGHT1);
        LSAVE[2] = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
        savedShade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        LIGHT_BUF.clear();
        GL11.glGetFloat(GL11.GL_LIGHT_MODEL_AMBIENT, LIGHT_BUF);
        for (int i = 0; i < 4; i++) savedAmb[i] = LIGHT_BUF.get(i);
        setupGuiItemLighting();
    }

    public static void itemLightingOff() {
        GL11.glShadeModel(savedShade);
        GL11.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, buf4(savedAmb[0], savedAmb[1], savedAmb[2], savedAmb[3]));
        if (!LSAVE[0]) GL11.glDisable(GL11.GL_LIGHT0);
        if (!LSAVE[1]) GL11.glDisable(GL11.GL_LIGHT1);
        if (!LSAVE[2]) GL11.glDisable(GL11.GL_COLOR_MATERIAL);
    }

    // raw replica of RenderHelper.enableGUIStandardItemLighting (two directional
    // lights rotated for the GUI, flat shading, 0.4 ambient). Light params aren't
    // cached by GlStateManager, and the enable bits are restored by the caller.
    private static final java.nio.FloatBuffer LIGHT_BUF = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private static java.nio.FloatBuffer buf4(float a, float b, float c, float d) {
        LIGHT_BUF.clear();
        LIGHT_BUF.put(a).put(b).put(c).put(d);
        LIGHT_BUF.flip();
        return LIGHT_BUF;
    }

    private static void setupGuiItemLighting() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glRotatef(-30f, 0f, 1f, 0f);
        GL11.glRotatef(165f, 1f, 0f, 0f);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_LIGHT1);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, buf4(0.16169f, 0.80845f, -0.56592f, 0f));
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, buf4(0.6f, 0.6f, 0.6f, 1f));
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_AMBIENT, buf4(0f, 0f, 0f, 1f));
        GL11.glLight(GL11.GL_LIGHT0, GL11.GL_SPECULAR, buf4(0f, 0f, 0f, 1f));
        GL11.glLight(GL11.GL_LIGHT1, GL11.GL_POSITION, buf4(-0.16169f, 0.80845f, 0.56592f, 0f));
        GL11.glLight(GL11.GL_LIGHT1, GL11.GL_DIFFUSE, buf4(0.6f, 0.6f, 0.6f, 1f));
        GL11.glLight(GL11.GL_LIGHT1, GL11.GL_AMBIENT, buf4(0f, 0f, 0f, 1f));
        GL11.glLight(GL11.GL_LIGHT1, GL11.GL_SPECULAR, buf4(0f, 0f, 0f, 1f));
        GL11.glPopMatrix();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, buf4(0.4f, 0.4f, 0.4f, 1f));
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String memoryText(boolean percent) {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory() / 1048576L;
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        if (percent) return "RAM " + (max > 0 ? used * 100 / max : 0) + "%";
        return "RAM " + used + " / " + max + " MB";
    }

    private String sessionText() {
        long sec = (System.currentTimeMillis() - startTime) / 1000L;
        return String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    static String clockText(boolean h24, boolean sec) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int hh = c.get(java.util.Calendar.HOUR_OF_DAY);
        int mm = c.get(java.util.Calendar.MINUTE);
        int ss = c.get(java.util.Calendar.SECOND);
        String suffix = "";
        if (!h24) { suffix = hh < 12 ? " AM" : " PM"; hh = hh % 12; if (hh == 0) hh = 12; }
        String t = sec ? String.format("%02d:%02d:%02d", hh, mm, ss) : String.format("%02d:%02d", hh, mm);
        return t + suffix;
    }
}
