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
import org.lwjgl.opengl.Display;

/**
 * Replaces the LWJGL window's title and taskbar icon with IEA branding. Minecraft sets
 * these once at display creation, so applying them from the first render frame sticks.
 * The icon is the same lime "IEA" badge as the launcher/logo, baked with Java2D into the
 * RGBA buffers LWJGL's Display.setIcon expects (16/32/64 px so Windows picks a crisp size).
 */
public final class WindowChrome {
    private static boolean applied = false;

    public static void applyOnce() {
        if (applied) return;
        applied = true;
        try { Display.setTitle("IEA Client 1.8.9"); } catch (Throwable ignored) { }
        try {
            Display.setIcon(new ByteBuffer[] { iconBuffer(16), iconBuffer(32), iconBuffer(64) });
        } catch (Throwable ignored) { }
    }

    private static ByteBuffer iconBuffer(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        float r = px * 0.22f;
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
                buf.put((byte) ((argb >> 16) & 0xFF)); // R
                buf.put((byte) ((argb >> 8) & 0xFF));  // G
                buf.put((byte) (argb & 0xFF));         // B
                buf.put((byte) ((argb >>> 24) & 0xFF));// A
            }
        }
        buf.flip();
        return buf;
    }
}
