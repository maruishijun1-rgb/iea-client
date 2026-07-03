package dev.iea.client.module;

import java.util.ArrayList;
import java.util.List;

/** A toggleable feature with optional per-module settings. */
public final class Module {
    public final String name;
    public final String category;
    public boolean enabled;
    public String descKey; // Lang key for a short description (shown on the settings page)
    public final List<Setting> settings = new ArrayList<Setting>();

    public Module(String name, String category, boolean enabledByDefault) {
        this.name = name;
        this.category = category;
        this.enabled = enabledByDefault;
    }

    public Module add(Setting s) {
        settings.add(s);
        return this;
    }

    public Module desc(String key) {
        this.descKey = key;
        return this;
    }

    public Setting get(String key) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).key.equals(key)) return settings.get(i);
        }
        return null;
    }

    public boolean bool(String key) {
        Setting s = get(key);
        return s != null && s.type == Setting.BOOL && s.bool;
    }

    public float num(String key, float def) {
        Setting s = get(key);
        // MODE stores its selected index in `num`, so it reads through here too.
        return (s != null && (s.type == Setting.NUMBER || s.type == Setting.MODE)) ? s.num : def;
    }

    public int keyCode(String key, int def) {
        Setting s = get(key);
        return (s != null && s.type == Setting.KEY) ? s.keyCode : def;
    }
}
