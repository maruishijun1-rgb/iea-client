package dev.iea.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Full-screen saturation (vibrance) post-process for the immediate-mode (LWJGL2)
 * pipeline. Each frame we grab the current framebuffer into a texture (glCopyTexImage2D —
 * no FBO, like MotionBlur) and redraw it 1:1 through a tiny GLSL fragment shader that
 * mixes each pixel toward/away from its luminance. sat: 0 = greyscale, 1 = unchanged,
 * >1 = more vivid. 1.8.9 runs on an OpenGL 2.x context so GLSL 1.10 is available; if the
 * driver can't compile the shader we silently disable (progFailed) and do nothing.
 */
public final class Saturation {
    private static int tex = 0, tw = 0, th = 0;
    private static int prog = 0, uSat = -1;
    private static boolean progFailed = false;

    // GLSL 1.10 (no #version): fixed-function vertex stage feeds gl_TexCoord[0].
    private static final String FRAG =
            "uniform sampler2D scene;\n" +
            "uniform float sat;\n" +
            "void main(){\n" +
            "  vec3 c = texture2D(scene, gl_TexCoord[0].st).rgb;\n" +
            "  float g = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  gl_FragColor = vec4(mix(vec3(g), c, sat), 1.0);\n" +
            "}\n";

    private static void ensureProgram() {
        if (prog != 0 || progFailed) return;
        try {
            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, FRAG);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                System.out.println("[IEA] Saturation shader compile failed: "
                        + GL20.glGetShaderInfoLog(fs, 512));
                GL20.glDeleteShader(fs); progFailed = true; return;
            }
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.out.println("[IEA] Saturation program link failed: "
                        + GL20.glGetProgramInfoLog(p, 512));
                GL20.glDeleteProgram(p); progFailed = true; return;
            }
            prog = p;
            uSat = GL20.glGetUniformLocation(prog, "sat");
            GL20.glUseProgram(prog);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "scene"), 0); // sampler on unit 0
            GL20.glUseProgram(0);
            System.out.println("[IEA] Saturation shader ready");
        } catch (Throwable t) {
            progFailed = true;
            System.out.println("[IEA] Saturation shader error: " + t);
        }
    }

    public static void render(int w, int h, float sat) {
        if (w <= 0 || h <= 0 || Math.abs(sat - 1f) < 0.01f) return; // 1.0 = no change
        ensureProgram();
        if (prog == 0) return;
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix(); GL11.glLoadIdentity();
            GL11.glOrtho(0, w, h, 0, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix(); GL11.glLoadIdentity();
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glEnable(GL11.GL_TEXTURE_2D);

                if (tex == 0 || tw != w || th != h) {
                    if (tex != 0) GL11.glDeleteTextures(tex);
                    tex = GL11.glGenTextures();
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    tw = w; th = h;
                } else {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                }
                // grab the current frame, then draw it back through the shader
                GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, 0, 0, w, h, 0);

                GL20.glUseProgram(prog);
                GL20.glUniform1f(uSat, sat);
                GL11.glColor4f(1f, 1f, 1f, 1f);
                // framebuffer-copied texture is bottom-left origin; flip V for the top-left ortho
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0, 0);
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0, h);
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w, h);
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w, 0);
                GL11.glEnd();
                GL20.glUseProgram(0);
            } finally {
                GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        } catch (Throwable ignored) { }
    }
}
