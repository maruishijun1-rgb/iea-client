package dev.iea.client.module;

/** A per-module setting: a boolean toggle, a numeric slider, a key, or a select (mode). */
public final class Setting {
    public static final int BOOL = 0, NUMBER = 1, KEY = 2, MODE = 3;

    public final int type;
    public final String key, name;
    public boolean bool;
    public float num, min, max, step;
    public int keyCode;
    public String[] options; // MODE: option labels (i18n keys); the selected index is `num`

    public Setting(String key, String name, boolean def) {
        this.type = BOOL;
        this.key = key;
        this.name = name;
        this.bool = def;
    }

    public Setting(String key, String name, int keyCode) {
        this.type = KEY;
        this.key = key;
        this.name = name;
        this.keyCode = keyCode;
    }

    public Setting(String key, String name, float def, float min, float max, float step) {
        this.type = NUMBER;
        this.key = key;
        this.name = name;
        this.num = def;
        this.min = min;
        this.max = max;
        this.step = step;
    }

    /** Select / dropdown: `options` are i18n keys; the chosen index is stored in `num`
     *  (so config save/load and Module.num() work unchanged). */
    public Setting(String key, String name, String[] options, int def) {
        this.type = MODE;
        this.key = key;
        this.name = name;
        this.options = options;
        this.num = def;
        this.min = 0;
        this.max = options.length - 1;
        this.step = 1;
    }
}
