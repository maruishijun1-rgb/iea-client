package dev.iea.client.render;

import org.lwjgl.opengl.GL11;

/**
 * Accumulation-buffer motion blur for the immediate-mode (LWJGL2) pipeline. Each frame
 * we draw the previous accumulated frame over the current one with alpha = amount, then
 * copy the blended result back into the accumulation texture. That exponential moving
 * average leaves motion trails. Called after the world is drawn but BEFORE the IEA HUD,
 * so the HUD stays crisp while the world blurs (like the old "Motion Blur" super-secret
 * setting). No framebuffer objects needed — works on the vanilla 1.8.9 GL context.
 */
public final class MotionBlur {
    private static int tex = 0;
    private static int tw = 0, th = 0;

    public static void render(int w, int h, float amount) {
        if (w <= 0 || h <= 0 || amount <= 0.001f) return;
        if (amount > 0.95f) amount = 0.95f;
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
            GL11.glOrtho(0, w, h, 0, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_TEXTURE_2D);

                if (tex == 0 || tw != w || th != h) {
                    // (re)create the accumulation texture and seed it from the current frame
                    if (tex != 0) GL11.glDeleteTextures(tex);
                    tex = GL11.glGenTextures();
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, 0, 0, w, h, 0);
                    tw = w; th = h;
                    return; // first frame: nothing to blend yet
                }

                // blend the accumulated previous frame over the current one
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                GL11.glColor4f(1f, 1f, 1f, amount);
                // the framebuffer-copied texture has a bottom-left origin; flip V so it
                // overlays the (top-left) ortho exactly aligned.
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0, 0);
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0, h);
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w, h);
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w, 0);
                GL11.glEnd();
                GL11.glColor4f(1f, 1f, 1f, 1f);

                // store the blended result for the next frame
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            } finally {
                GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        } catch (Throwable ignored) { }
    }
}
