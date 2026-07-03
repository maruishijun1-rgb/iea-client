package dev.iea.client.gui;

import java.util.HashMap;

/**
 * Tiny time-based animation store. Each key holds a current value that eases
 * toward a target every frame using frame-rate-independent exponential smoothing
 * (so it feels the same at 60 or 800 FPS). Call {@link #tick()} once per frame
 * before any {@link #to} calls.
 */
public final class Anim {
    private static final HashMap<Object, Float> vals = new HashMap<Object, Float>();
    private static long last = 0;
    private static float dt = 0f;

    public static void tick() {
        long now = System.nanoTime();
        if (last == 0) last = now;
        dt = (now - last) / 1_000_000_000f;
        last = now;
        if (dt > 0.1f) dt = 0.1f; else if (dt < 0) dt = 0;
    }

    /** Ease the value for key toward target; higher speed = snappier. Returns the eased value. */
    public static float to(Object key, float target, float speed) {
        Float c = vals.get(key);
        float cur = (c == null) ? target : c;
        float k = 1f - (float) Math.exp(-speed * dt);
        cur += (target - cur) * k;
        if (Math.abs(target - cur) < 0.0008f) cur = target;
        vals.put(key, cur);
        return cur;
    }

    /** Force a key to a value (e.g. reset to 0 so it animates in next frame). */
    public static void set(Object key, float v) { vals.put(key, v); }
}
