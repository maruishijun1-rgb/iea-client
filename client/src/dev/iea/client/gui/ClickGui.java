package dev.iea.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import dev.iea.client.Config;
import dev.iea.client.Lang;
import dev.iea.client.Theme;
import dev.iea.client.hud.HudManager;
import dev.iea.client.module.Module;
import dev.iea.client.module.Modules;
import dev.iea.client.module.Setting;
import dev.iea.client.render.Font;
import dev.iea.client.render.Gl;
import dev.iea.client.render.Logo;

/**
 * Lunar-style in-game window: a centered framed window over a dimmed game, with
 * category tabs, a grid of square module tiles (gear -> per-module settings page
 * with a back button), and a drag-to-move HUD layout editor.
 */
public final class ClickGui {
    public enum State { CLOSED, MENU, SETTINGS, HUD_EDIT }

    private State state = State.CLOSED;
    private int tab = 0;
    private String search = ""; // module search filter (typed in the menu)
    private Module selected;
    private boolean prevDown = false;
    private Setting draggingSlider;
    private Setting listening; // a KEY setting waiting for a key press
    private final HudManager hud;

    public boolean isBinding() { return listening != null; }

    private final String[] tabCats = { null, "HUD", "Input", "Render", "Player" };

    private String tabLabel(int i) {
        String c = tabCats[i];
        if (c == null) return Lang.t("tab_all");
        if (c.equals("Input")) return Lang.t("cat_input");
        if (c.equals("Render")) return Lang.t("cat_render");
        if (c.equals("Player")) return Lang.t("cat_player");
        return Lang.t("cat_hud");
    }

    public ClickGui(HudManager hud) { this.hud = hud; }

    public boolean isOpen() { return state != State.CLOSED; }

    // latest HUD data, so the layout editor can show the real elements (not just outlines)
    private Font hudFontRef;
    private Logo logoRef;
    private int fpsRef, cpsLRef, cpsRRef;
    public void setHudData(Font hudFont, Logo logo, int fps, int cpsL, int cpsR) {
        this.hudFontRef = hudFont; this.logoRef = logo;
        this.fpsRef = fps; this.cpsLRef = cpsL; this.cpsRRef = cpsR;
    }

    public void onToggleKey() {
        if (state == State.CLOSED) { state = State.MENU; Anim.set("open", 0f); } // animate in
        else { state = State.CLOSED; search = ""; Config.save(hud); }
    }

    public void onEscape() {
        if (state == State.SETTINGS) state = State.MENU;
        else if (state != State.CLOSED) { state = State.CLOSED; search = ""; Config.save(hud); }
    }

    // typed-character input, fed from the menu's key-event drain (Hook.onFrame). Drives the
    // module search box. Ignored while binding a key setting (that capture takes the key),
    // and only in the module grid (MENU) state.
    public void onCharTyped(char c, int key) {
        if (state != State.MENU || listening != null) return;
        if (key == Keyboard.KEY_BACK) {
            if (search.length() > 0) search = search.substring(0, search.length() - 1);
        } else if (c >= 32 && c != 127 && search.length() < 24) {
            search += c;
        }
    }

    private float gridScroll = 0;
    private int wheel = 0;

    public void render(Font row, Font title, Font big, Logo logo, int mx, int my, boolean down, int scroll) {
        if (state == State.CLOSED) return;
        Anim.tick();
        int w = Display.getWidth(), h = Display.getHeight();
        boolean clicked = down && !prevDown;
        prevDown = down;
        this.wheel = scroll;

        // capture a key for keybind settings
        if (listening != null) {
            for (int k = 1; k < 256; k++) {
                if (Keyboard.isKeyDown(k)) {
                    if (k != Keyboard.KEY_ESCAPE) listening.keyCode = k;
                    listening = null;
                    break;
                }
            }
        }

        if (state == State.HUD_EDIT) {
            renderEdit(row, title, w, h, mx, my, down, clicked);
            return;
        }

        // open transition: fade + a small upward slide (frame-rate independent)
        float open = Anim.to("open", 1f, 16f);
        float ease = 1f - (1f - open) * (1f - open); // ease-out
        Gl.alpha = ease;

        // dim the game; the window floats in the middle like an app window
        Gl.rect(0, 0, w, h, 0xCC0A0B10);

        float pw = Math.min(780, w - 50), ph = Math.min(520, h - 50);
        float px = (w - pw) / 2f, py = (h - ph) / 2f + (1f - ease) * 18f, pad = 20;

        card(px, py, pw, ph, 16, 0xFF111319, Theme.BORDER);

        // --- header ---
        float hx = px + pad, hy = py + pad, hw = pw - pad * 2, hh = 58;
        card(hx, hy, hw, hh, 12, 0xFF1C1F29, Theme.BORDER);
        logo.draw(hx + 14, hy + 13, 32);
        big.draw("IEA CLIENT", hx + 58, hy + (hh - big.getHeight()) / 2f, Theme.TEXT);
        float beW = 124, beX = hx + hw - beW - 14;
        if (button(beX, hy + 13, beW, hh - 26, Lang.t("edit_hud"), title, false, mx, my) && clicked)
            state = State.HUD_EDIT;
        float lgW = 54, lgX = beX - lgW - 10;
        if (button(lgX, hy + 13, lgW, hh - 26, Lang.badge(), title, false, mx, my) && clicked) {
            Lang.toggle();
            Config.save(hud);
        }

        float bodyY = hy + hh + 16;
        if (state == State.MENU) renderGrid(row, title, px, py, pw, ph, pad, bodyY, mx, my, clicked);
        else renderSettings(row, title, big, px, py, pw, ph, pad, bodyY, mx, my, down, clicked);
        Gl.alpha = 1f; // restore master alpha after the open-fade
    }

    // ---------------- mods grid ----------------
    private void renderGrid(Font row, Font title, float px, float py, float pw, float ph, float pad,
                            float bodyY, int mx, int my, boolean clicked) {
        // tabs
        float tx = px + pad, ty = bodyY;
        for (int i = 0; i < tabCats.length; i++) {
            String label = tabLabel(i);
            float tw = title.getWidth(label) + 40;
            boolean hover = inside(mx, my, tx, ty, tw, 34);
            boolean sel = tab == i;
            float sv = Anim.to("tabsel" + i, sel ? 1f : 0f, 14f);
            float hv = Anim.to("tabhov" + i, hover ? 1f : 0f, 16f);
            // neutral fill (no green wash); a subtle green edge + green text mark the active tab
            int fill = Gl.lerp(0xFF171A22, Theme.PANEL2, Math.max(hv, sv * 0.6f));
            card(tx, ty, tw, 34, 9, fill, Gl.lerp(Theme.BORDER, Theme.accentA(0x55), sv));
            title.drawCentered(label, tx + tw / 2f, ty + 17, Gl.lerp(Theme.TEXT, Theme.ACCENT, sv));
            if (hover && clicked) { tab = i; search = ""; }
            tx += tw + 10;
        }

        // search box (right side of the tab row); typing is captured globally while the
        // menu is open, so it's always "focused" — click it to clear.
        float shW = 200, shH = 34, shX = px + pw - pad - shW, shY = ty;
        boolean shHover = inside(mx, my, shX, shY, shW, shH);
        card(shX, shY, shW, shH, 9, shHover ? Theme.PANEL2 : 0xFF171A22,
                search.isEmpty() ? Theme.BORDER : Theme.ACCENT);
        float stx = shX + 12, sty = shY + (shH - row.getHeight()) / 2f;
        row.draw(search.isEmpty() ? Lang.t("search") : search, stx, sty,
                search.isEmpty() ? Theme.MUTED : Theme.TEXT);
        // blinking caret (the box is always focused while the menu is open) — a real bar,
        // since the IEA font's "_" glyph sits too low to read as a cursor
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = stx + (search.isEmpty() ? 0 : row.getWidth(search)) + 1;
            Gl.rect(cx, shY + 8, 1.5f, shH - 16, Theme.ACCENT);
        }
        if (shHover && clicked && !search.isEmpty()) search = "";

        // grid of tiles (scrollable)
        List<Module> list = filtered();
        float gx = px + pad, gy = ty + 50, gw = pw - pad * 2;
        int cols = 3;
        float gap = 14, tileW = (gw - (cols - 1) * gap) / cols, tileH = 100;

        // small headroom so the top row's border/AA isn't clipped by the scissor edge
        float pad0 = 4f;
        int rows = (list.size() + cols - 1) / cols;
        float visibleH = (py + ph - pad) - gy;
        float totalH = rows * (tileH + gap) + pad0;
        float maxScroll = Math.max(0, totalH - visibleH);
        gridScroll = clampF(gridScroll - wheel * 0.4f, 0, maxScroll);

        // clip the tile area to the window
        int scH = Display.getHeight();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int) gx, (int) (scH - (gy + visibleH)), (int) gw, (int) visibleH);

        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            float cx = gx + (i % cols) * (tileW + gap);
            float cyBase = gy + pad0 + (i / cols) * (tileH + gap) - gridScroll;
            boolean hover = inside(mx, my, cx, cyBase, tileW, tileH) && my >= gy && my <= gy + visibleH;
            float hv = Anim.to("tile" + m.name, hover ? 1f : 0f, 16f);
            float ea = Anim.to("en" + m.name, m.enabled ? 1f : 0f, 14f);
            float cy = cyBase;
            // neutral fill regardless of state; the green edge + green text signal "enabled",
            // so there's no large green wash to strain the eyes
            int fill = Gl.lerp(0xFF181B23, 0xFF20242E, hv);
            int border = Gl.lerp(Theme.BORDER, Theme.accentA(0x66), ea);
            card(cx, cy, tileW, tileH, 12, fill, border);

            title.drawCentered(m.name, cx + tileW / 2f, cy + 42, Gl.lerp(Theme.TEXT, Theme.ACCENT, ea));
            String st = m.enabled ? Lang.t("on") : Lang.t("off");
            row.drawCentered(st, cx + tileW / 2f, cy + tileH - 26, Gl.lerp(Theme.MUTED, Theme.ACCENT, ea));

            // settings (gear-ish kebab) top-right, inset a bit from the edge
            boolean gearHover = inside(mx, my, cx + tileW - 42, cy + 6, 32, 24);
            kebab(cx + tileW - 26, cy + 18, gearHover ? Theme.ACCENT : Theme.MUTED);
            if (clicked) {
                if (gearHover) { selected = m; state = State.SETTINGS; setScroll = 0; }
                else if (hover) m.enabled = !m.enabled;
            }
        }
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        // scrollbar hint
        if (maxScroll > 0) {
            float trackH = visibleH * (visibleH / totalH);
            float trackY = gy + (visibleH - trackH) * (gridScroll / maxScroll);
            Gl.roundedRect(px + pw - pad + 2, trackY, 3, trackH, 1.5f, Theme.ACCENT);
        }
    }

    private static float clampF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ---------------- per-module settings ----------------
    private float setScroll = 0, setContentH = 0;

    private void renderSettings(Font row, Font title, Font big, float px, float py, float pw, float ph,
                                float pad, float bodyY, int mx, int my, boolean down, boolean clicked) {
        float bx = px + pad;
        if (button(bx, bodyY, 90, 32, "← " + Lang.t("back"), title, false, mx, my) && clicked) {
            state = State.MENU;
            return;
        }
        if (selected == null) return;
        float rw = pw - pad * 2;

        // scrollable content area below the back button (long setting lists were
        // overflowing past the panel)
        float top = bodyY + 44, bottom = py + ph - pad, visibleH = bottom - top;
        float maxScroll = Math.max(0, setContentH - visibleH);
        setScroll = clampF(setScroll - wheel * 0.4f, 0, maxScroll);
        boolean canClick = clicked && my >= top && my <= bottom;

        int scH = Display.getHeight();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int) px, (int) (scH - bottom), (int) pw, (int) visibleH);

        float yo = top - setScroll;
        big.draw(selected.name, bx, yo, Theme.TEXT);
        float cy = yo + big.getHeight() + 4;
        row.draw(Lang.t("category") + ": " + selected.category, bx + 2, cy, Theme.MUTED);
        float dy = cy + row.getHeight() + 6;
        if (selected.descKey != null)
            dy = drawWrapped(Lang.t(selected.descKey), bx + 2, dy, rw - 4, row, Theme.MUTED) + 6;

        float rowH = 44, gap = 6, ry = dy + 2;

        // enable
        settingRow(bx, ry, rw, rowH, Lang.t("enabled"), title);
        toggle(bx + rw - 70, ry + (rowH - 28) / 2f, selected.enabled, "tgEn" + selected.name);
        if (canClick && inside(mx, my, bx, ry, rw, rowH)) selected.enabled = !selected.enabled;
        ry += rowH + gap;

        // per-module settings
        for (int i = 0; i < selected.settings.size(); i++) {
            Setting s = selected.settings.get(i);
            settingRow(bx, ry, rw, rowH, Lang.t(s.name), title);
            if (s.type == Setting.BOOL) {
                toggle(bx + rw - 70, ry + (rowH - 28) / 2f, s.bool, s);
                if (canClick && inside(mx, my, bx, ry, rw, rowH)) s.bool = !s.bool;
            } else if (s.type == Setting.KEY) {
                float kw = 110, kx = bx + rw - kw - 16, kyy = ry + (rowH - 28) / 2f;
                boolean kh = inside(mx, my, kx, kyy, kw, 28);
                String label = (listening == s) ? Lang.t("press_key") : keyName(s.keyCode);
                card(kx, kyy, kw, 28, 8, kh ? Theme.PANEL2 : 0xFF171A22,
                        listening == s ? Theme.ACCENT : Theme.BORDER);
                row.drawCentered(label, kx + kw / 2f, kyy + 14, listening == s ? Theme.ACCENT : Theme.TEXT);
                if (kh && canClick) listening = s;
            } else if (s.type == Setting.MODE) {
                float kw = 150, kx = bx + rw - kw - 16, kyy = ry + (rowH - 28) / 2f;
                int n = s.options.length, idx = Math.max(0, Math.min(n - 1, (int) s.num));
                // left half = previous, right half = next (big, easy-to-hit click zones)
                boolean leftHalf = inside(mx, my, kx, kyy, kw / 2f, 28);
                boolean rightHalf = inside(mx, my, kx + kw / 2f, kyy, kw / 2f, 28);
                card(kx, kyy, kw, 28, 8, (leftHalf || rightHalf) ? Theme.PANEL2 : 0xFF171A22, Theme.BORDER);
                row.drawCentered("<", kx + 12, kyy + 14, leftHalf ? Theme.ACCENT : Theme.MUTED);
                row.drawCentered(">", kx + kw - 12, kyy + 14, rightHalf ? Theme.ACCENT : Theme.MUTED);
                row.drawCentered(Lang.t(s.options[idx]), kx + kw / 2f, kyy + 14, Theme.TEXT);
                if (canClick && leftHalf) s.num = (idx - 1 + n) % n;
                else if (canClick && rightHalf) s.num = (idx + 1) % n;
            } else {
                slider(s, bx + rw - 196, ry + rowH / 2f, 110, mx, my, down, row);
            }
            ry += rowH + gap;
        }

        // reset position (only for HUD elements)
        if (hud.has(selected.name)) {
            if (button(bx, ry, rw, 44, Lang.t("reset_pos"), title, false, mx, my) && canClick)
                hud.resetOne(selected.name);
            row.draw(Lang.t("note_pos"), bx + 2, ry + 58, Theme.MUTED);
            ry += 58 + row.getHeight();
        }
        setContentH = (ry + 8) - yo;

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        // scrollbar hint
        if (maxScroll > 0) {
            float trackH = visibleH * (visibleH / setContentH);
            float trackY = top + (visibleH - trackH) * (setScroll / maxScroll);
            Gl.roundedRect(px + pw - pad + 2, trackY, 3, trackH, 1.5f, Theme.ACCENT);
        }

        if (!down) draggingSlider = null;
    }

    // word/char-wrapped paragraph; returns the y just below the last line
    private float drawWrapped(String text, float x, float y, float maxW, Font f, int color) {
        float lh = f.getHeight() + 3;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { f.draw(line.toString(), x, y, color); y += lh; line.setLength(0); continue; }
            line.append(c);
            if (f.getWidth(line.toString()) > maxW && line.length() > 1) {
                line.deleteCharAt(line.length() - 1);
                f.draw(line.toString(), x, y, color); y += lh;
                line.setLength(0); line.append(c);
            }
        }
        if (line.length() > 0) { f.draw(line.toString(), x, y, color); y += lh; }
        return y;
    }

    private void settingRow(float x, float y, float w, float h, String label, Font title) {
        card(x, y, w, h, 10, 0xFF181B23, Theme.BORDER);
        title.draw(label, x + 22, y + (h - title.getHeight()) / 2f, Theme.TEXT);
    }

    private void slider(Setting s, float x, float cy, float w, int mx, int my, boolean down, Font font) {
        float h = 6, y = cy - h / 2f;
        if (down && draggingSlider == null && inside(mx, my, x - 4, cy - 12, w + 8, 24)) draggingSlider = s;
        if (draggingSlider == s) {
            float t = Math.max(0, Math.min(1, (mx - x) / w));
            float v = s.min + t * (s.max - s.min);
            v = Math.round(v / s.step) * s.step;
            s.num = Math.max(s.min, Math.min(s.max, v));
        }
        float frac = (s.num - s.min) / (s.max - s.min);
        Gl.roundedRect(x, y, w, h, h / 2f, Theme.TRACK);
        Gl.roundedRect(x, y, w * frac, h, h / 2f, Theme.ACCENT2);
        float kx = x + w * frac;
        Gl.roundedRect(kx - 7, cy - 7, 14, 14, 7, Theme.ACCENT);
        String val = (s.step >= 1) ? String.valueOf((int) s.num) : String.format("%.2f", s.num);
        font.drawCentered(val, x + w + 30, cy, Theme.TEXT);
    }

    private List<Module> filtered() {
        List<Module> out = new ArrayList<Module>();
        // a non-empty search filters across ALL categories by name; otherwise use the tab
        String q = search.toLowerCase();
        if (!q.isEmpty()) {
            for (int i = 0; i < Modules.ALL.size(); i++) {
                Module m = Modules.ALL.get(i);
                if (m.name.toLowerCase().contains(q)) out.add(m);
            }
            return out;
        }
        String cat = tabCats[tab];
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module m = Modules.ALL.get(i);
            if (cat == null || m.category.equals(cat)) out.add(m);
        }
        return out;
    }

    // ---------------- HUD layout editor ----------------
    private void renderEdit(Font row, Font title, int w, int h, int mx, int my, boolean down, boolean clicked) {
        Gl.rect(0, 0, w, h, 0x66000000);
        hud.editDrag(mx, my, down, clicked);
        // draw the real HUD elements so you can see what you're positioning
        if (hudFontRef != null) hud.render(hudFontRef, logoRef, fpsRef, cpsLRef, cpsRRef);
        hud.drawOutlines(row);
        String hint = Lang.t("edit_hud") + "   " + Lang.t("hud_hint");
        float tw = title.getWidth(hint), bw = tw + 44, bx = (w - bw) / 2f;
        card(bx, 16, bw, 36, 12, 0xF214161D, Theme.accentA(0x66));
        title.draw(hint, bx + 22, 16 + (36 - title.getHeight()) / 2f, Theme.ACCENT);
    }

    // ---------------- helpers ----------------
    private void card(float x, float y, float w, float h, float r, int fill, int border) {
        Gl.roundedRect(x, y, w, h, r, fill);             // flat fill, AA edges
        Gl.roundedOutline(x, y, w, h, r, 1.2f, border);  // thin crisp hairline border
    }

    private boolean button(float x, float y, float w, float h, String label, Font f, boolean primary, int mx, int my) {
        boolean hover = inside(mx, my, x, y, w, h);
        float hv = Anim.to("btn" + label, hover ? 1f : 0f, 16f);
        int fill = primary ? Gl.lerp(Theme.accentA(0x33), Theme.ACCENT2, hv) : Gl.lerp(0xFF171A22, Theme.PANEL2, hv);
        card(x, y, w, h, 10, fill, primary ? Theme.accentA(0x66) : Theme.BORDER);
        int col = primary ? Gl.lerp(Theme.ACCENT, Theme.DARK, hv) : Theme.TEXT;
        f.drawCentered(label, x + w / 2f, y + h / 2f, col);
        return hover;
    }

    // animated switch: the knob slides and the track/knob colors crossfade
    private void toggle(float x, float y, boolean on, Object key) {
        float tw = 50, th = 28;
        float p = Anim.to(key, on ? 1f : 0f, 16f);
        Gl.roundedRect(x, y, tw, th, th / 2f, Gl.lerp(Theme.TRACK, Theme.ACCENT2, p));
        float kn = th - 8;
        float kx = (x + 4) + (tw - kn - 8) * p; // slide from left (x+4) to right
        Gl.roundedRect(kx, y + 4, kn, kn, kn / 2f, Gl.lerp(Theme.MUTED, Theme.ACCENT, p));
    }

    private void kebab(float cx, float cy, int color) {
        for (int i = -1; i <= 1; i++) Gl.roundedRect(cx - 2 + i * 7, cy - 2, 4, 4, 2, color);
    }

    private static String keyName(int code) {
        if (code <= 0) return "NONE";
        String n = Keyboard.getKeyName(code);
        return n != null ? n : ("KEY" + code);
    }

    private static boolean inside(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
