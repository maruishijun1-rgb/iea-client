package dev.iea.client.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * The IEA badge — the same design as the launcher's app icon: a lime
 * gradient rounded square with bold white "IEA". Baked once with Java2D into a
 * GL texture, then drawn as a quad.
 */
public final class Logo {
    private int tex;

    public void init(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        float r = px * 0.22f; // matches the launcher icon's corner radius
        // solid lime badge with bold black "IEA" — same mark as the launcher/app icon
        g.setColor(new Color(0xA3, 0xE6, 0x35));
        g.fill(new RoundRectangle2D.Float(0, 0, px, px, r * 2, r * 2));

        g.setColor(new Color(0x0E, 0x0F, 0x14));
        g.setFont(new Font("Segoe UI", Font.BOLD, (int) (px * 0.42f)));
        FontMetrics fm = g.getFontMetrics();
        String s = "IEA";
        float tx = (px - fm.stringWidth(s)) / 2f;
        float baseline = (px - fm.getHeight()) / 2f + fm.getAscent();
        g.drawString(s, tx, baseline);
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
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, px, px, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }

    public void draw(float x, float y, float size) {
        // restore the previous binding after: a raw bind left dangling desyncs
        // GlStateManager's texture cache and garbles later vanilla-font draws
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glColor4f(1f, 1f, 1f, Gl.alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(x, y + size);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(x + size, y + size);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(x + size, y);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x, y);
        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
}
