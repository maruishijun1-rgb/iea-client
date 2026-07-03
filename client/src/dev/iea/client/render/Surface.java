package dev.iea.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Thin 2D drawing scope. Saves/restores the active texture unit + bound texture
 * around our drawing so Minecraft's GlStateManager cache stays in sync (avoids
 * sky flicker / missing world textures). Direct rendering (no FBO) — the FBO
 * supersampling path was removed because it interfered with MC's framebuffer.
 */
public final class Surface {
    private static int savedActive, savedTex;
    private static boolean savedLightmapOn;
    private static boolean stateSaved;

    public static void begin(int w, int h) {
        stateSaved = false;
        try {
            savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            savedTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            // The world render often leaves the lightmap on texture unit 1 ENABLED. Our
            // HUD/GUI quads carry no unit-1 texcoords, so an enabled lightmap multiplies
            // them by garbage — a red-ish tint + scrambled glyphs/icons (worst in the
            // Nether / certain light levels). Disable it for our pass, restore on end so
            // GlStateManager's cache still matches and the world keeps its lightmap.
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            savedLightmapOn = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            if (savedLightmapOn) GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            stateSaved = true;
        } catch (Throwable ignored) { }
        Gl.begin2D(w, h);
    }

    public static void end(int w, int h) {
        Gl.end2D();
        if (stateSaved) {
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                if (savedLightmapOn) GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex);
                GL13.glActiveTexture(savedActive);
            } catch (Throwable ignored) { }
        }
    }
}
