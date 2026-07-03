package dev.iea.client.render;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Clean-room "custom sky" (MCPatcher / OptiFine resource-pack sky format). This reads
 * the PUBLIC file format (assets/minecraft/mcpatcher/sky/world0/skyN.properties + a
 * source image) straight from the selected resource pack on disk, and renders it as a
 * skybox around the camera from inside our renderSky hook.
 *
 * The source image is a 3x2 grid of six 90-degree views (tile N at u=(N%3)/3,
 * v=(N/3)/2, each 1/3 x 1/2): 0=below, 1=above, and 2..5 the four horizontal
 * directions. Faces are placed by the same rotation sequence the reference
 * implementation uses so packs line up seam-for-seam. Because the render hook REPLACES
 * the vanilla sky, the box is drawn OPAQUE (fully covering the fog-colour clear); only
 * "blend=alpha" packs get transparency. Additive blending here washed the sky out white
 * by day and orange at sunset (it added to the time-of-day clear colour).
 *
 * v2: loads sky1 only, static box (no day-night rotation/fades yet).
 */
public final class Sky {
    private static boolean tried = false;
    private static boolean ready = false;
    private static int tex = 0;
    private static boolean blendAlpha = false; // "blend=alpha" -> transparent; else opaque

    /** Lazily load on the render thread (GL context present); cached after the first try. */
    public static boolean isReady() {
        if (!tried) {
            tried = true;
            GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
            try { load(); }
            catch (Throwable t) { System.out.println("[IEA] CustomSky load error: " + t); }
            finally { GL11.glPopAttrib(); }
        }
        return ready;
    }

    private static void load() {
        File gameDir = new File(System.getProperty("user.dir", "."));
        File packsDir = new File(gameDir, "resourcepacks");
        List<String> packs = selectedPacks(gameDir);
        // highest-priority pack is last in the options list; check those first
        for (int i = packs.size() - 1; i >= 0; i--) {
            File pack = new File(packsDir, packs.get(i));
            byte[] props = readPack(pack, "assets/minecraft/mcpatcher/sky/world0/sky1.properties");
            if (props == null) continue;
            try {
                Properties pr = new Properties();
                pr.load(new ByteArrayInputStream(props));
                String src = pr.getProperty("source", "./sky1.png").trim();
                // only "blend=alpha" packs use transparency; everything else replaces the
                // sky opaquely (we skip the vanilla sky, so the box must cover it fully)
                blendAlpha = "alpha".equalsIgnoreCase(pr.getProperty("blend", "").trim());
                byte[] img = readPack(pack, resolveSource(src));
                if (img == null) continue;
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img));
                if (bi == null) continue;
                tex = upload(bi);
                if (tex != 0) {
                    ready = true;
                    System.out.println("[IEA] CustomSky loaded from pack: " + packs.get(i));
                    return;
                }
            } catch (Throwable t) {
                System.out.println("[IEA] CustomSky parse error: " + t);
            }
        }
        System.out.println("[IEA] CustomSky: no mcpatcher sky found in selected resource packs");
    }

    // map a properties "source" to a pack-relative resource path
    private static String resolveSource(String s) {
        if (s.startsWith("./")) return "assets/minecraft/mcpatcher/sky/world0/" + s.substring(2);
        if (s.startsWith("assets/")) return s;
        int c = s.indexOf(':');
        if (c >= 0) return "assets/" + s.substring(0, c) + "/" + s.substring(c + 1);
        return "assets/minecraft/" + s;
    }

    // resourcePacks:["a.zip","b"] line from options.txt (1.8.9 format)
    private static List<String> selectedPacks(File gameDir) {
        List<String> out = new ArrayList<String>();
        File opt = new File(gameDir, "options.txt");
        if (!opt.isFile()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(opt));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("resourcePacks:")) continue;
                int a = line.indexOf('['), b = line.lastIndexOf(']');
                if (a < 0 || b <= a) break;
                for (String tok : line.substring(a + 1, b).split(",")) {
                    String s = tok.trim();
                    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
                    if (s.startsWith("file/")) s = s.substring(5); // newer format prefix (harmless)
                    if (!s.isEmpty()) out.add(s);
                }
                break;
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception e) { }
        }
        return out;
    }

    // read one entry from a pack that is either a folder or a .zip
    private static byte[] readPack(File pack, String entry) {
        try {
            if (pack.isDirectory()) {
                File f = new File(pack, entry);
                return f.isFile() ? readAll(new FileInputStream(f)) : null;
            }
            if (pack.isFile() && pack.getName().toLowerCase().endsWith(".zip")) {
                ZipFile zf = new ZipFile(pack);
                try {
                    ZipEntry e = zf.getEntry(entry);
                    return e != null ? readAll(zf.getInputStream(e)) : null;
                } finally {
                    zf.close();
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { in.close(); } catch (Exception e) { }
        }
    }

    private static int upload(BufferedImage bi) {
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < px.length; i++) {
            int argb = px[i];
            buf.put((byte) ((argb >> 16) & 0xFF));
            buf.put((byte) ((argb >> 8) & 0xFF));
            buf.put((byte) (argb & 0xFF));
            buf.put((byte) ((argb >>> 24) & 0xFF));
        }
        buf.flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    /** Draw the custom sky box around the camera (called from the renderSky hook). */
    public static void render(float partial) {
        if (!ready) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            // We REPLACE the vanilla sky (the hook skips it), so draw the skybox OPAQUE —
            // it fully covers the fog-colour clear. Additive blending instead ADDED the
            // texture on top of that clear, so the sky washed out white by day and looked
            // like a sunset when the clear went orange. Only "blend=alpha" packs get
            // alpha transparency.
            if (blendAlpha) {
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            drawBox(); // static sky (no day-night rotation yet)
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    // Skybox from the 3x2 source grid. Every tile is drawn as the same local floor quad
    // (the y=-100 plane) and oriented by the format's canonical rotation sequence, so a
    // pack's seams line up exactly as its author intended: tile 4 straight ahead, tiles
    // 1/0 above/below, tiles 5/2/3 the remaining three horizontal directions.
    private static void drawBox() {
        GL11.glPushMatrix();
        GL11.glRotatef(90f, 1f, 0f, 0f);
        GL11.glRotatef(-90f, 0f, 0f, 1f);
        tile(4);
        GL11.glPushMatrix();
        GL11.glRotatef(90f, 1f, 0f, 0f);
        tile(1);
        GL11.glPopMatrix();
        GL11.glPushMatrix();
        GL11.glRotatef(-90f, 1f, 0f, 0f);
        tile(0);
        GL11.glPopMatrix();
        GL11.glRotatef(90f, 0f, 0f, 1f);
        tile(5);
        GL11.glRotatef(90f, 0f, 0f, 1f);
        tile(2);
        GL11.glRotatef(90f, 0f, 0f, 1f);
        tile(3);
        GL11.glPopMatrix();
    }

    // one 1/3 x 1/2 tile of the source image on the local y=-100 plane
    private static void tile(int i) {
        float s = 100f;
        float u0 = (i % 3) / 3f, v0 = (i / 3) / 2f;
        float u1 = u0 + 1f / 3f, v1 = v0 + 0.5f;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex3f(-s, -s, -s);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex3f(-s, -s,  s);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex3f( s, -s,  s);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex3f( s, -s, -s);
        GL11.glEnd();
    }
}
