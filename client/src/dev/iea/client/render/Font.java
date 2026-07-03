package dev.iea.client.render;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Dynamic Unicode font with 2x supersampling for smooth (non-pixelated) text.
 * Glyphs (incl. Japanese) are rasterized at 2x with AWT into a GL atlas and
 * drawn downscaled. Vertical centering uses the measured ink bounds so text sits
 * truly centered (font ascent/descent leaves uneven margins).
 */
public final class Font {
    private static final int ATLAS = 2048;
    private static final int SS = 2;   // supersample factor
    private static final int PAD = 2;  // atlas-px margin around each glyph

    /**
     * When the IEAFont module is OFF, the whole client UI (this Font) delegates to the
     * vanilla FontRenderer so the GUI and the game share one font — flip the IEAFont
     * toggle and *everything* switches together. Hook installs the bridge.
     */
    public interface Vanilla {
        boolean active();                                  // vanilla mode on?
        float rawWidth(String s);                          // vanilla getStringWidth (native px)
        void rawDraw(String s, float x, float y, int argb);// vanilla drawString (native scale, no shadow)
    }
    public static Vanilla vanilla;

    private float vanScale = 1f; // GL scale so vanilla glyphs match this font's visible size

    private int tex;
    private int penX = PAD, penY = PAD, rowH = 0;
    private int ascent, heightSS;      // atlas px
    private float vCenter;             // ink center within a cell (atlas px)
    private java.awt.Font awtFont, fbFont;
    private FontMetrics metrics, fbMetrics;
    private final HashMap<Character, int[]> glyphs = new HashMap<Character, int[]>(); // c -> {x,y,w,h,adv} (atlas px)

    public void init(int size) { init(size, true); }

    public void init(int size, boolean bold) {
        int style = bold ? java.awt.Font.BOLD : java.awt.Font.PLAIN;
        awtFont = new java.awt.Font("Yu Gothic UI", style, size * SS);
        // logical SansSerif substitutes across all installed fonts — used for any
        // character Yu Gothic can't display (symbols etc.)
        fbFont = new java.awt.Font(java.awt.Font.SANS_SERIF, style, size * SS);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(awtFont);
        metrics = pg.getFontMetrics();
        ascent = metrics.getAscent();
        heightSS = metrics.getAscent() + metrics.getDescent();
        pg.setFont(fbFont);
        fbMetrics = pg.getFontMetrics();
        pg.dispose();

        measureInkCenter();
        // vanilla FONT_HEIGHT is 9 with a ~7.5px glyph body; scale it to this font's
        // measured ink height (÷6.5 instead of 7.5 = ~15% larger, so vanilla's small/thin
        // CJK glyphs are big enough to read in the GUI)
        vanScale = getInkHeight() / 6.5f;

        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        ByteBuffer empty = BufferUtils.createByteBuffer(ATLAS * ATLAS * 4);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, ATLAS, ATLAS, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, empty);
    }

    // Render a representative set and find the real top/bottom ink rows so we can
    // center on the glyph body rather than the font's (uneven) ascent/descent.
    private void measureInkCenter() {
        int h = heightSS + PAD * 2;
        int wImg = 256;
        BufferedImage img = new BufferedImage(wImg, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(awtFont);
        g.setColor(Color.WHITE);
        // cap-height range (no descenders) so headings/labels center nicely
        g.drawString("A国8あ", PAD, PAD + ascent);
        g.dispose();
        int min = -1, max = -1;
        for (int y = 0; y < h; y++) {
            boolean ink = false;
            for (int x = 0; x < wImg; x++) if (((img.getRGB(x, y) >>> 24) & 0xFF) > 16) { ink = true; break; }
            if (ink) { if (min < 0) min = y; max = y; }
        }
        if (min < 0) {
            vCenter = h / 2f;
            inkTopSS = PAD;
            inkHeightSS = heightSS;
        } else {
            vCenter = (min + max) / 2f;
            inkTopSS = min;
            inkHeightSS = max - min + 1;
        }
    }

    private float inkTopSS, inkHeightSS; // measured glyph-body bounds (atlas px)

    /** Distance from the glyph cell top to the first ink row, in display px. */
    public float getInkTop() { return inkTopSS / SS; }

    /** Height of the glyph body (cap/kana height) in display px. */
    public float getInkHeight() { return inkHeightSS / SS; }

    public int getHeight() {
        if (vanilla != null && vanilla.active()) return Math.round(9f * vanScale);
        return Math.round((float) heightSS / SS);
    }

    /** True when this font (or its system fallback) has a glyph for the char. */
    public boolean canDisplay(char c) {
        return awtFont.canDisplay(c) || fbFont.canDisplay(c);
    }

    private FontMetrics metricsFor(char c) {
        return awtFont.canDisplay(c) ? metrics : fbMetrics;
    }

    public float getWidthF(String s) {
        if (vanilla != null && vanilla.active()) return vanilla.rawWidth(s) * vanScale;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += metricsFor(c).charWidth(c);
        }
        return (float) w / SS;
    }

    public int getWidth(String s) { return Math.round(getWidthF(s)); }

    private int[] ensure(char c) {
        int[] g = glyphs.get(c);
        if (g != null) return g;

        boolean main = awtFont.canDisplay(c);
        FontMetrics fm = main ? metrics : fbMetrics;
        int adv = fm.charWidth(c);
        if (adv <= 0) adv = metrics.charWidth('?');
        int gw = adv + PAD * 2;
        int gh = heightSS + PAD * 2;

        if (penX + gw > ATLAS - 1) { penX = PAD; penY += rowH + 1; rowH = 0; }
        if (gh > rowH) rowH = gh;
        if (penY + gh > ATLAS) {
            // atlas full: wrap to the top and forget the old glyphs (their cells get
            // overwritten — keeping the map would show garbled characters)
            penX = PAD; penY = PAD; rowH = gh;
            glyphs.clear();
        }

        BufferedImage img = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = img.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        gg.setFont(main ? awtFont : fbFont);
        gg.setColor(Color.WHITE);
        gg.drawString(String.valueOf(c), PAD, PAD + ascent);
        gg.dispose();

        ByteBuffer buf = BufferUtils.createByteBuffer(gw * gh * 4);
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >> 8) & 0xFF));
                buf.put((byte) (argb & 0xFF));
                buf.put((byte) ((argb >>> 24) & 0xFF));
            }
        }
        buf.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, penX, penY, gw, gh,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

        g = new int[] { penX, penY, gw, gh, adv };
        glyphs.put(c, g);
        penX += gw + 1;
        return g;
    }

    /** Centered on (cx, cy), using measured ink bounds for true vertical centering. */
    public void drawCentered(String s, float cx, float cy, int argb) {
        if (vanilla != null && vanilla.active()) {
            // vanilla getStringWidth includes a 1px right gap per char; drop the last one
            // so the real glyph ink (not the trailing space) is what gets centred, else
            // text drifts left. Centre the glyph body (≈ getInkHeight tall) on (cx, cy).
            float w = Math.max(0f, vanilla.rawWidth(s) - 1f) * vanScale;
            drawVanilla(s, cx - w / 2f, cy - getInkHeight() / 2f, argb);
            return;
        }
        float x = cx - getWidthF(s) / 2f - (float) PAD / SS; // -PAD removes the left cell margin
        float y = cy - vCenter / SS;
        draw(s, x, y, argb);
    }

    /** Centered at the vanilla font's NATIVE size (scale 1.0) when in vanilla mode — used
     *  for the restyled vanilla GUI buttons so their labels match vanilla's own size.
     *  Falls back to the normal (scaled) drawCentered when IEAFont is on. */
    public void drawCenteredNative(String s, float cx, float cy, int argb) {
        if (vanilla != null && vanilla.active()) {
            float w = Math.max(0f, vanilla.rawWidth(s) - 1f);   // native px, no vanScale
            // vanilla centres a label with its top at (centreY - 4); callers pass cy about
            // 1.5px above the box centre, so -2.5 lands the text exactly like vanilla
            drawVanillaScaled(s, cx - w / 2f, cy - 2.5f, argb, 1f);
            return;
        }
        drawCentered(s, cx, cy, argb);
    }

    // vanilla-font path: draw scaled about (x, y) as the top-left, folding the master
    // alpha into the colour (vanilla drawString doesn't know about Gl.alpha)
    private void drawVanilla(String s, float x, float y, int argb) {
        drawVanillaScaled(s, x, y, argb, vanScale);
    }

    private void drawVanillaScaled(String s, float x, float y, int argb, float scale) {
        int a = (argb >>> 24) & 0xFF; if (a == 0) a = 255;
        a = Math.round(a * Gl.alpha); if (a > 255) a = 255; if (a < 0) a = 0;
        int col = (a << 24) | (argb & 0xFFFFFF);
        // the 2D pass draws untextured shapes with GL_TEXTURE_2D OFF; vanilla's font is a
        // texture, so without this every glyph renders as a solid ■. Enable it just for
        // the draw and restore, so the following Gl.rect() calls aren't textured/tinted.
        boolean hadTex = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        GL11.glScalef(scale, scale, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        vanilla.rawDraw(s, 0f, 0f, col);
        if (!hadTex) GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f); // reset colour so later untextured shapes aren't tinted
        GL11.glPopMatrix();
    }

    /** Plain text with a 1px dark drop shadow, so it stays readable on any background
     *  without needing a boxed panel behind it. */
    public void drawShadow(String s, float x, float y, int argb) {
        int a = (argb >>> 24) & 0xFF;
        draw(s, x + 1, y + 1, (a * 180 / 255) << 24); // black shadow at reduced alpha
        draw(s, x, y, argb);
    }

    /** Draws with the glyph cell top-left at (x, y). */
    public void draw(String s, float x, float y, int argb) {
        if (vanilla != null && vanilla.active()) {
            // place the vanilla glyph body where the IEA font's ink would sit (a left cell
            // margin + the ink top offset), so switching fonts doesn't shift text up/left
            drawVanilla(s, x + (float) PAD / SS, y + getInkTop(), argb);
            return;
        }
        for (int i = 0; i < s.length(); i++) ensure(s.charAt(i));

        float a = ((argb >>> 24) & 0xFF) / 255f * Gl.alpha;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float gc = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glColor4f(r, gc, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        float cx = x;
        float yi = Math.round(y); // snap baseline to a pixel row for crisp text
        for (int i = 0; i < s.length(); i++) {
            int[] g = glyphs.get(s.charAt(i));
            if (g == null) continue;
            float u0 = (float) g[0] / ATLAS, u1 = (float) (g[0] + g[2]) / ATLAS;
            float v0 = (float) g[1] / ATLAS, v1 = (float) (g[1] + g[3]) / ATLAS;
            float qw = (float) g[2] / SS, qh = (float) g[3] / SS;
            float xi = Math.round(cx); // snap each glyph to a pixel column
            GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(xi, yi + qh);
            GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(xi + qw, yi + qh);
            GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(xi + qw, yi);
            GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(xi, yi);
            cx += (float) g[4] / SS;
        }
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
