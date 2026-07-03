package dev.iea.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import dev.iea.client.hud.HudElement;
import dev.iea.client.hud.HudManager;
import dev.iea.client.module.Module;
import dev.iea.client.module.Modules;
import dev.iea.client.module.Setting;

/** Persists module toggles + HUD anchors to iea-client.properties in the game dir. */
public final class Config {
    private static File file() {
        return new File(System.getProperty("user.dir", "."), "iea-client.properties");
    }

    public static void load(HudManager hud) {
        Properties p = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file());
            p.load(in);
        } catch (Exception e) {
            return;
        } finally {
            close(in);
        }
        String lang = p.getProperty("language");
        if (lang != null) Lang.current = lang;
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module m = Modules.ALL.get(i);
            String v = p.getProperty("module." + m.name);
            if (v != null) m.enabled = Boolean.parseBoolean(v);
            for (int j = 0; j < m.settings.size(); j++) {
                Setting s = m.settings.get(j);
                String k = "set." + m.name + "." + s.key;
                if (s.type == Setting.BOOL) {
                    String sv = p.getProperty(k);
                    if (sv != null) s.bool = Boolean.parseBoolean(sv);
                } else if (s.type == Setting.KEY) {
                    s.keyCode = (int) getF(p, k, s.keyCode);
                } else {
                    s.num = getF(p, k, s.num);
                }
            }
        }
        for (int i = 0; i < hud.elements.size(); i++) {
            HudElement e = hud.elements.get(i);
            String k = "hud." + e.name + ".";
            e.ax = (int) getF(p, k + "ax", e.ax);
            e.ay = (int) getF(p, k + "ay", e.ay);
            e.mx = getF(p, k + "mx", e.mx);
            e.my = getF(p, k + "my", e.my);
        }
    }

    public static void save(HudManager hud) {
        Properties p = new Properties();
        p.setProperty("language", Lang.current);
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Module m = Modules.ALL.get(i);
            p.setProperty("module." + m.name, String.valueOf(m.enabled));
            for (int j = 0; j < m.settings.size(); j++) {
                Setting s = m.settings.get(j);
                String k = "set." + m.name + "." + s.key;
                String v = s.type == Setting.BOOL ? String.valueOf(s.bool)
                        : s.type == Setting.KEY ? String.valueOf(s.keyCode)
                        : String.valueOf(s.num);
                p.setProperty(k, v);
            }
        }
        for (int i = 0; i < hud.elements.size(); i++) {
            HudElement e = hud.elements.get(i);
            String k = "hud." + e.name + ".";
            p.setProperty(k + "ax", String.valueOf(e.ax));
            p.setProperty(k + "ay", String.valueOf(e.ay));
            p.setProperty(k + "mx", String.valueOf(e.mx));
            p.setProperty(k + "my", String.valueOf(e.my));
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file());
            p.store(out, "IEA Client");
        } catch (Exception e) {
            // ignore
        } finally {
            close(out);
        }
    }

    private static float getF(Properties p, String k, float def) {
        try { return Float.parseFloat(p.getProperty(k)); } catch (Exception e) { return def; }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception e) { /* ignore */ }
    }
}
