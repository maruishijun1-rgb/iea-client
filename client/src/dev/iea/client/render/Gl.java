package dev.iea.client.render;

import org.lwjgl.opengl.GL11;

/**
 * Minimal immediate-mode 2D drawing on top of the game frame. begin2D() saves
 * all GL state and sets up a top-left origin ortho projection; end2D() restores
 * everything so the game's own rendering is untouched.
 */
public final class Gl {
    public static void begin2D(int width, int height) {
        alpha = 1f;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, height, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE); // ensure our shapes aren't culled by the game's state
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    public static void end2D() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    // smoother corners for larger radii
    private static int segments(float r) {
        int s = Math.round(r);
        if (s < 4) s = 4;
        if (s > 20) s = 20;
        return s;
    }

    /** Global alpha multiplier (1 = opaque). Used for per-element opacity. */
    public static float alpha = 1f;

    private static void color(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f * alpha;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        GL11.glColor4f(r, g, b, a);
    }

    /** Linear blend between two ARGB colors (t in 0..1). */
    public static int lerp(int a, int b, float t) {
        if (t < 0) t = 0; else if (t > 1) t = 1;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = (int) (aa + (ba - aa) * t), or = (int) (ar + (br - ar) * t);
        int og = (int) (ag + (bg - ag) * t), ob = (int) (ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    /** Lighten the RGB toward white by amt (0..1), preserving alpha — for soft highlights. */
    public static int lighten(int argb, float amt) {
        int a = (argb >>> 24) & 0xFF;
        int rgb = lerp(0xFF000000 | (argb & 0xFFFFFF), 0xFFFFFFFF, amt) & 0xFFFFFF;
        return (a << 24) | rgb;
    }

    private static void colorY(int top, int bot, float y0, float h, float vy) {
        color(lerp(top, bot, h == 0 ? 0 : (vy - y0) / h));
    }

    public static void rect(float x, float y, float w, float h, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        color(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x, y);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Filled rounded rectangle (matches the launcher's rounded panels). */
    private static final float AA = 1.0f; // anti-alias edge width in px

    private static float[][] cornersOf(float x, float y, float w, float h, float r) {
        return new float[][] {
            { x + w - r, y + r, -90, 0 },   // top-right
            { x + w - r, y + h - r, 0, 90 }, // bottom-right
            { x + r, y + h - r, 90, 180 },   // bottom-left
            { x + r, y + r, 180, 270 },      // top-left
        };
    }

    // A quad strip between two radii along the rounded perimeter, with a per-edge
    // alpha so it can be a solid band (aA=aB=1) or an AA feather (1 -> 0).
    private static void ring(float[][] cs, int seg, float rA, float aA, float rB, float aB, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float base = ((argb >>> 24) & 0xFF) / 255f * alpha;
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        float fAx = Float.NaN, fAy = 0, fBx = 0, fBy = 0;
        for (float[] c : cs) {
            for (int i = 0; i <= seg; i++) {
                double ang = Math.toRadians(c[2] + (c[3] - c[2]) * (double) i / seg);
                float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
                float ax = c[0] + cos * rA, ay = c[1] + sin * rA;
                float bx = c[0] + cos * rB, by = c[1] + sin * rB;
                GL11.glColor4f(r, g, b, base * aA); GL11.glVertex2f(ax, ay);
                GL11.glColor4f(r, g, b, base * aB); GL11.glVertex2f(bx, by);
                if (Float.isNaN(fAx)) { fAx = ax; fAy = ay; fBx = bx; fBy = by; }
            }
        }
        GL11.glColor4f(r, g, b, base * aA); GL11.glVertex2f(fAx, fAy);
        GL11.glColor4f(r, g, b, base * aB); GL11.glVertex2f(fBx, fBy);
        GL11.glEnd();
    }

    /** Filled rounded rectangle with a 1px anti-aliased edge (crisp, not jagged). */
    public static void roundedRect(float x, float y, float w, float h, float r, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        r = Math.min(r, Math.min(w, h) / 2f);
        float[][] cs = cornersOf(x, y, w, h, r);
        int seg = segments(r);
        color(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(x + w / 2f, y + h / 2f);
        float firstX = Float.NaN, firstY = 0;
        for (float[] c : cs) {
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(c[2] + (c[3] - c[2]) * (double) i / seg);
                float px = (float) (c[0] + Math.cos(a) * r);
                float py = (float) (c[1] + Math.sin(a) * r);
                if (Float.isNaN(firstX)) { firstX = px; firstY = py; }
                GL11.glVertex2f(px, py);
            }
        }
        GL11.glVertex2f(firstX, firstY); // close the fan
        GL11.glEnd();
        ring(cs, seg, r, 1f, r + AA, 0f, argb); // anti-aliased outer edge
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Filled rounded rectangle with a vertical gradient (top color -> bottom color). */
    public static void roundedRectV(float x, float y, float w, float h, float r, int top, int bot) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        r = Math.min(r, Math.min(w, h) / 2f);
        float[][] corners = {
            { x + w - r, y + r, -90, 0 },
            { x + w - r, y + h - r, 0, 90 },
            { x + r, y + h - r, 90, 180 },
            { x + r, y + r, 180, 270 },
        };
        int seg = segments(r);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        colorY(top, bot, y, h, y + h / 2f);
        GL11.glVertex2f(x + w / 2f, y + h / 2f);
        float firstX = Float.NaN, firstY = 0;
        for (float[] c : corners) {
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(c[2] + (c[3] - c[2]) * (double) i / seg);
                float px = (float) (c[0] + Math.cos(a) * r);
                float py = (float) (c[1] + Math.sin(a) * r);
                if (Float.isNaN(firstX)) { firstX = px; firstY = py; }
                colorY(top, bot, y, h, py);
                GL11.glVertex2f(px, py);
            }
        }
        colorY(top, bot, y, h, firstY);
        GL11.glVertex2f(firstX, firstY);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Rounded-rectangle outline (thickness t) with anti-aliased inner & outer edges. */
    public static void roundedOutline(float x, float y, float w, float h, float r, float t, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        r = Math.min(r, Math.min(w, h) / 2f);
        float ri = Math.max(0, r - t);
        float[][] cs = cornersOf(x, y, w, h, r);
        int seg = segments(r);
        ring(cs, seg, ri, 1f, r, 1f, argb);          // solid band
        ring(cs, seg, r, 1f, r + AA, 0f, argb);       // outer AA feather
        if (ri > AA) ring(cs, seg, ri, 1f, ri - AA, 0f, argb); // inner AA feather
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    public static void tri(float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        color(argb);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x3, y3);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Vertical gradient from top color to bottom color. */
    public static void gradientV(float x, float y, float w, float h, int top, int bottom) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        color(bottom); GL11.glVertex2f(x, y + h);
        color(bottom); GL11.glVertex2f(x + w, y + h);
        color(top); GL11.glVertex2f(x + w, y);
        color(top); GL11.glVertex2f(x, y);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Horizontal gradient from c1 (left) to c2 (right). */
    public static void gradientH(float x, float y, float w, float h, int c1, int c2) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        color(c1); GL11.glVertex2f(x, y + h);
        color(c2); GL11.glVertex2f(x + w, y + h);
        color(c2); GL11.glVertex2f(x + w, y);
        color(c1); GL11.glVertex2f(x, y);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }
}
