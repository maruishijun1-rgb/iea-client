package dev.iea.client.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * A procedurally-baked, tileable cobblestone texture for the main-menu background.
 * Grey rounded stones with light/dark edges on dark mortar; uploaded as a GL_REPEAT
 * texture so it can be tiled across the whole screen with a single quad.
 */
public final class Cobble {
    private int tex;

    public void init(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x2C, 0x2E, 0x33)); // mortar
        g.fillRect(0, 0, px, px);
        Random rnd = new Random(20240607L);
        int n = 4, cell = px / n;
        for (int gy = 0; gy < n; gy++) {
            for (int gx = 0; gx < n; gx++) {
                int cx = gx * cell, cy = gy * cell;
                int pad = 1 + rnd.nextInt(2);
                int sx = cx + pad, sy = cy + pad;
                int sw = cell - pad * 2 - rnd.nextInt(2), sh = cell - pad * 2 - rnd.nextInt(2);
                if (sw < 2 || sh < 2) continue;
                int base = 0x6E + rnd.nextInt(0x26); // grey 0x6E..0x93
                g.setColor(new Color(base, base, Math.min(255, base + 3)));
                g.fillRoundRect(sx, sy, sw, sh, 3, 3);
                int hi = Math.min(255, base + 0x16);
                g.setColor(new Color(hi, hi, hi));
                g.drawLine(sx, sy, sx + sw - 1, sy);
                g.drawLine(sx, sy, sx, sy + sh - 1);
                int lo = Math.max(0, base - 0x20);
                g.setColor(new Color(lo, lo, lo));
                g.drawLine(sx, sy + sh - 1, sx + sw - 1, sy + sh - 1);
                g.drawLine(sx + sw - 1, sy, sx + sw - 1, sy + sh - 1);
            }
        }
        g.dispose();

        ByteBuffer buf = BufferUtils.createByteBuffer(px * px * 4);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) ((argb >> 16) & 0xFF));
                buf.put((byte) ((argb >> 8) & 0xFF));
                buf.put((byte) (argb & 0xFF));
                buf.put((byte) ((argb >>> 24) & 0xFF));
            }
        }
        buf.flip();

        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, px, px, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }

    /** Tile the texture over (x,y,w,h); tilePx = on-screen size of one tile; bright = 0..1 dim. */
    public void draw(float x, float y, float w, float h, float tilePx, float bright) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); // restore to keep the cache in sync
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glColor4f(bright, bright, bright, 1f);
        float u = w / tilePx, v = h / tilePx;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, v); GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u, v); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u, 0); GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x, y);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
