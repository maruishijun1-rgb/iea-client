package dev.iea.client;

/**
 * Colors (0xAARRGGBB) mirroring the launcher's lime-on-dark theme.
 */
public final class Theme {
    public static final int BACKDROP = 0xCC0E0F14; // dim behind the panel
    public static final int PANEL    = 0xFF16181F;
    public static final int PANEL2   = 0xFF1C1F29;
    public static final int ROW      = 0xFF14161D;
    public static final int BORDER   = 0xFF3A4252; // lighter so frames are clearly visible
    public static final int TEXT     = 0xFFE7E9EE;
    public static final int MUTED    = 0xFFA9AEBC; // brighter so small/hint text isn't faint
    // Accent is USER-CHANGEABLE (Theme module), so these are NOT final — a final constant
    // would be inlined into other classes at compile time and never update at runtime.
    public static int ACCENT   = 0xFF9DC24F; // theme accent (default softened lime)
    public static int ACCENT2  = 0xFF6E9A2E; // darker accent, derived from ACCENT
    public static final int DARK     = 0xFF0E0F14; // text on lime header
    public static final int TRACK    = 0xFF2A2E3A; // toggle track (off)

    public static final int DEFAULT_ACCENT = 0x9DC24F; // the stock lime (RGB, no alpha)

    /** Set the accent from an RGB value (no alpha). ACCENT2 is a darker shade of it,
     *  used for slider fills / toggle tracks / pressed keys. */
    public static void setAccent(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        ACCENT = 0xFF000000 | (rgb & 0xFFFFFF);
        ACCENT2 = 0xFF000000 | ((int) (r * 0.63f) << 16) | ((int) (g * 0.63f) << 8) | (int) (b * 0.63f);
    }

    /** The accent colour with a custom alpha byte — for subtle borders / washes. */
    public static int accentA(int alpha) {
        return ((alpha & 0xFF) << 24) | (ACCENT & 0xFFFFFF);
    }
}
