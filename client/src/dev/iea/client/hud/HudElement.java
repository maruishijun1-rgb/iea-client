package dev.iea.client.hud;

/**
 * A HUD item anchored to a screen edge/corner so it keeps its place when the
 * window is resized. (ax, ay) = anchor, (mx, my) = margin from that edge.
 * (x, y, w, h) are the resolved screen rect, recomputed each frame.
 */
public final class HudElement {
    public static final int LEFT = 0, RIGHT = 1, CENTERX = 2;
    public static final int TOP = 0, BOTTOM = 1, CENTERY = 2;

    public final String name;
    public int ax, ay;       // anchor
    public float mx, my;     // margin from the anchored edge
    public float x, y, w, h; // resolved rect (per frame)

    public HudElement(String name, int ax, int ay, float mx, float my) {
        this.name = name;
        this.ax = ax;
        this.ay = ay;
        this.mx = mx;
        this.my = my;
    }
}
