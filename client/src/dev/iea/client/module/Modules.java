package dev.iea.client.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Module registry. Only features that genuinely work without Minecraft
 * obfuscation mappings are registered here (HUD overlays driven by LWJGL input).
 * Game-state features (sprint, zoom, fullbright, …) need the mappings step.
 */
public final class Modules {
    public static final List<Module> ALL = new ArrayList<Module>();
    private static boolean inited = false;

    public static void init() {
        if (inited) return;
        inited = true;
        common(add("Watermark", "HUD", true)).desc("d.watermark");
        common(add("FPS", "HUD", true)).desc("d.fps")
                .add(new Setting("label", "s.label", true));
        common(add("CPS", "HUD", true)).desc("d.cps")
                .add(new Setting("split", "s.split", true));
        common(add("Clock", "HUD", false)).desc("d.clock")
                .add(new Setting("h24", "s.h24", true))
                .add(new Setting("sec", "s.sec", true));
        common(add("Memory", "HUD", true)).desc("d.memory")
                .add(new Setting("percent", "s.percent", false));
        common(add("ArrayList", "HUD", true)).desc("d.arraylist")
                .add(new Setting("bg", "s.bg", true));
        common(add("SessionTimer", "HUD", false)).desc("d.session");
        common(add("ComboCounter", "HUD", false)).desc("d.combo");
        common(add("ServerAddress", "HUD", false)).desc("d.serveraddr");
        // Bedwars: flash a big title when a teammate dies (chat + scoreboard team)
        add("DeathAlert", "HUD", false).desc("d.deathalert")
                .add(new Setting("scale", "s.scale", 2.2f, 1f, 4f, 0.1f))
                .add(new Setting("seconds", "s.seconds", 3f, 1f, 6f, 0.5f))
                .add(new Setting("red", "s.red", 255f, 0f, 255f, 5f))
                .add(new Setting("green", "s.green", 80f, 0f, 255f, 5f))
                .add(new Setting("blue", "s.blue", 80f, 0f, 255f, 5f))
                .add(new Setting("debug", "s.debug", false));
        common(add("Keystrokes", "Input", true)).desc("d.keystrokes")
                .add(new Setting("space", "s.space", true))
                .add(new Setting("mouse", "s.mouse", false));
        common(add("MouseStrokes", "Input", false)).desc("d.mousestrokes");
        // crosshair is fixed at centre (size is applied in its own draw, about the centre)
        add("Crosshair", "Render", false).desc("d.crosshair")
                .add(new Setting("type", "s.type", new String[] { "s.cx_cross", "s.cx_gap", "s.cx_dot",
                        "s.cx_t", "s.cx_circle", "s.cx_square", "s.cx_x", "s.cx_arrow" }, 0))
                .add(new Setting("size", "s.scale", 1f, 0.5f, 3f, 0.1f));
        // PvP info HUDs
        common(add("Reach", "HUD", false)).desc("d.reach");
        common(add("Coordinates", "HUD", false)).desc("d.coords");
        common(add("Ping", "HUD", false)).desc("d.ping");
        common(add("PotionHUD", "HUD", false)).desc("d.potionhud");
        common(add("ArmorStatus", "HUD", false)).desc("d.armor")
                .add(new Setting("horizontal", "s.horiz", false))
                .add(new Setting("grow", "s.grow", false));
        // game-state features (use mappings; not HUD elements)
        add("Fullbright", "Render", false).desc("d.fullbright");
        add("Zoom", "Render", false).desc("d.zoom")
                .add(new Setting("key", "s.key", org.lwjgl.input.Keyboard.KEY_C))
                .add(new Setting("zoom", "s.zoom", 4f, 2f, 8f, 0.5f));
        add("ToggleSprint", "Player", false).desc("d.togglesprint")
                .add(new Setting("key", "s.key", org.lwjgl.input.Keyboard.KEY_LCONTROL));
        add("NoHurtCam", "Player", false).desc("d.nohurtcam");
        add("SwingSpeed", "Player", false).desc("d.swingspeed")
                .add(new Setting("speed", "s.speed", 2f, 1f, 5f, 1f));
        // recolor the entity damage flash (RendererLivingEntity.setBrightness hook)
        add("HitColor", "Render", false).desc("d.hitcolor")
                .add(new Setting("red", "s.red", 85f, 0f, 255f, 5f))
                .add(new Setting("green", "s.green", 140f, 0f, 255f, 5f))
                .add(new Setting("blue", "s.blue", 255f, 0f, 255f, 5f));
        // block overlay: colored box on the targeted block (RenderGlobal hook)
        add("BlockOverlay", "Render", false).desc("d.blockoverlay")
                .add(new Setting("red", "s.red", 163f, 0f, 255f, 5f))
                .add(new Setting("green", "s.green", 230f, 0f, 255f, 5f))
                .add(new Setting("blue", "s.blue", 53f, 0f, 255f, 5f))
                .add(new Setting("alpha", "s.alpha", 110f, 0f, 220f, 5f));
        // hitbox: render each entity's REAL (vanilla) bounding box, coloured per category
        // (enemy / teammate / mob / arrow / pearl / projectile / other), with an eye-line.
        add("Hitbox", "Render", false).desc("d.hitbox")
                .add(new Setting("eyeline", "s.eyeline", true))
                .add(new Setting("width", "s.width", 3f, 1f, 8f, 0.5f))
                .add(new Setting("alpha", "s.alpha", 255f, 40f, 255f, 5f))
                .add(new Setting("en_r", "s.hb_en_r", 255f, 0f, 255f, 5f))
                .add(new Setting("en_g", "s.hb_en_g", 60f, 0f, 255f, 5f))
                .add(new Setting("en_b", "s.hb_en_b", 60f, 0f, 255f, 5f))
                .add(new Setting("te_r", "s.hb_te_r", 60f, 0f, 255f, 5f))
                .add(new Setting("te_g", "s.hb_te_g", 255f, 0f, 255f, 5f))
                .add(new Setting("te_b", "s.hb_te_b", 90f, 0f, 255f, 5f))
                .add(new Setting("mo_r", "s.hb_mo_r", 255f, 0f, 255f, 5f))
                .add(new Setting("mo_g", "s.hb_mo_g", 210f, 0f, 255f, 5f))
                .add(new Setting("mo_b", "s.hb_mo_b", 60f, 0f, 255f, 5f))
                .add(new Setting("ar_r", "s.hb_ar_r", 230f, 0f, 255f, 5f))
                .add(new Setting("ar_g", "s.hb_ar_g", 230f, 0f, 255f, 5f))
                .add(new Setting("ar_b", "s.hb_ar_b", 230f, 0f, 255f, 5f))
                .add(new Setting("pe_r", "s.hb_pe_r", 190f, 0f, 255f, 5f))
                .add(new Setting("pe_g", "s.hb_pe_g", 90f, 0f, 255f, 5f))
                .add(new Setting("pe_b", "s.hb_pe_b", 255f, 0f, 255f, 5f))
                .add(new Setting("pr_r", "s.hb_pr_r", 90f, 0f, 255f, 5f))
                .add(new Setting("pr_g", "s.hb_pr_g", 220f, 0f, 255f, 5f))
                .add(new Setting("pr_b", "s.hb_pr_b", 255f, 0f, 255f, 5f))
                .add(new Setting("ot_r", "s.hb_ot_r", 170f, 0f, 255f, 5f))
                .add(new Setting("ot_g", "s.hb_ot_g", 170f, 0f, 255f, 5f))
                .add(new Setting("ot_b", "s.hb_ot_b", 170f, 0f, 255f, 5f));
        // hit particles: a burst at the target when you land a hit (attackEntity +
        // RenderManager.doRenderEntity hooks; anchored to the hit entity)
        add("HitParticles", "Render", false).desc("d.hitparticles")
                .add(new Setting("count", "s.count", 12f, 1f, 500f, 1f))
                .add(new Setting("type", "s.type", new String[] { "s.hp_magic", "s.hp_crit" }, 0));
        // TNT fuse countdown above primed TNT — drawn with Minecraft's own nametag renderer
        // (Render.renderLivingLabel), so it looks exactly like a nametag (fixed size).
        add("TNTTimer", "Render", false).desc("d.tnttimer");
        // LevelHead (Hypixel): other players' network level above their head. Needs a
        // Hypixel API key in iea-hypixel-key.txt placed in the game folder.
        add("LevelHead", "Render", false).desc("d.levelhead")
                .add(new Setting("type", "s.leveltype", new String[] { "s.lvl_network", "s.lvl_bedwars",
                        "s.lvl_skywars", "s.lvl_fkdr", "s.lvl_wins" }, 0))
                .add(new Setting("self", "s.lvl_self", true));
        // ItemPhysics: lay dropped items flat. A floating item needs two axes to centre it:
        // height (vertical place in the hitbox) and depth (slide along the 90° flip axis).
        add("ItemPhysics", "Render", false).desc("d.itemphysics")
                .add(new Setting("height", "s.ip_height", 0f, -0.3f, 0.5f, 0.01f))
                .add(new Setting("depth", "s.ip_depth", 0.35f, -0.3f, 0.5f, 0.01f));
        // SoundTuner: per-sound volume multipliers (SoundManager.getNormalizedVolume hook).
        add("SoundTuner", "Player", false).desc("d.soundtuner")
                .add(new Setting("explode", "s.snd_explode", 1f, 0f, 3f, 0.05f))
                .add(new Setting("eat", "s.snd_eat", 1f, 0f, 3f, 0.05f))
                .add(new Setting("drink", "s.snd_drink", 1f, 0f, 3f, 0.05f))
                .add(new Setting("levelup", "s.snd_levelup", 1f, 0f, 3f, 0.05f))
                .add(new Setting("orb", "s.snd_orb", 1f, 0f, 3f, 0.05f))
                .add(new Setting("pop", "s.snd_pop", 1f, 0f, 3f, 0.05f))
                .add(new Setting("bow", "s.snd_bow", 1f, 0f, 3f, 0.05f))
                .add(new Setting("hurt", "s.snd_hurt", 1f, 0f, 3f, 0.05f))
                .add(new Setting("fire", "s.snd_fire", 1f, 0f, 3f, 0.05f))
                .add(new Setting("portal", "s.snd_portal", 1f, 0f, 3f, 0.05f))
                .add(new Setting("anvil", "s.snd_anvil", 1f, 0f, 3f, 0.05f))
                .add(new Setting("click", "s.snd_click", 1f, 0f, 3f, 0.05f));
        // motion blur over the world (accumulation blend in onFrame)
        add("MotionBlur", "Render", false).desc("d.motionblur")
                .add(new Setting("amount", "s.amount", 0.5f, 0.1f, 0.9f, 0.05f));
        // full-screen saturation: 0 = greyscale, 1 = normal, 2 = very vivid
        add("Saturation", "Render", false).desc("d.saturation")
                .add(new Setting("amount", "s.saturation", 1f, 0f, 2f, 0.05f));
        // static FOV: drops the sprint/speed/bow FOV zoom (world stays a fixed FOV)
        add("NoFov", "Render", false).desc("d.nofov");
        // user-changeable UI accent colour (RGB); defaults to the stock lime. Hidden from the
        // grid — edited from the dedicated Theme tab in the ClickGui.
        add("Theme", "Render", true).desc("d.theme").hide()
                .add(new Setting("r", "s.red", 163, 0, 255, 1))
                .add(new Setting("g", "s.green", 230, 0, 255, 1))
                .add(new Setting("b", "s.blue", 53, 0, 255, 1));
        // Dynamic FPS: cap the loop to ~30fps while the window is unfocused. Hidden client
        // setting (Settings tab), not a gameplay module.
        add("DynamicFps", "Render", true).desc("d.dynfps").hide();
        // replace the vanilla font everywhere (FontRenderer hooks) AND the client's own
        // GUI/HUD text — one toggle swaps the font for the game and the UI together. Hidden
        // (Settings tab): it's a client-wide preference, not a gameplay module.
        add("IEAFont", "Render", true).desc("d.ieafont").hide();
        // NOTE: CustomSky is NOT a module — it's a built-in feature that applies a resource
        // pack's custom sky automatically (like OptiFine/Lunar). The RenderGlobal.renderSky
        // hook stays in place; Hook.customSkyOn() activates it only when sky data is loaded.
        // restyle vanilla GUI screens: title-menu skin + ClickGui-style buttons.
        // default ON (preserves the original always-on behaviour); off = vanilla look.
        // Hidden (Settings tab): a client-wide preference, not a gameplay module.
        add("IEAGui", "Render", true).desc("d.ieagui").hide();
        // IEA-styled hotbar (GuiIngame.renderHotbar replacement)
        add("Hotbar", "Render", false).desc("d.hotbar")
                .add(new Setting("smooth", "s.smoothsel", true))
                .add(new Setting("bars", "s.statbars", false));
        // 1.7-style held-item animations (ItemRenderer hooks)
        add("OldAnimations", "Render", false).desc("d.oldanim")
                .add(new Setting("swing", "s.swing", true))
                .add(new Setting("sneak", "s.sneak", true))
                .add(new Setting("equip", "s.equip", true));
        // Chat optimisation: replace vanilla chat with a custom renderer — collapse repeated
        // messages into one "×N" line, keep more history, show a player head per line.
        // The GuiNewChat.drawChat hook is skipped only while this is ON (default off = vanilla).
        add("ChatOptimize", "Chat", false).desc("d.chatopt")
                .add(new Setting("compress", "s.chat_compress", true))  // collapse repeats -> ×N
                .add(new Setting("heads", "s.chat_heads", false))       // player head per line (WIP: off)
                .add(new Setting("infinite", "s.chat_infinite", true))  // keep long history / scroll
                .add(new Setting("lines", "s.chat_lines", 200, 40, 1000, 20)) // history cap
                .add(new Setting("opacity", "s.chat_opacity", 60, 0, 100, 5)); // bg opacity %
        // Translator: adds a small button beside each chat line to translate it into a chosen
        // language (Google free endpoint, no key). Works on its own — enabling it turns on the
        // custom chat renderer too, so the button can be drawn/clicked. Item text: WIP.
        add("Translator", "Chat", false).desc("d.translator")
                .add(new Setting("lang", "s.tr_lang", new String[] { "s.tr_ja", "s.tr_en", "s.tr_zh",
                        "s.tr_ko", "s.tr_es", "s.tr_fr", "s.tr_de", "s.tr_ru", "s.tr_pt", "s.tr_it" }, 0))
                .add(new Setting("items", "s.tr_items", false)); // auto-translate item tooltips on hover
    }

    /** Google language codes matching the Translator "lang" enum order above. */
    public static final String[] TR_LANGS = { "ja", "en", "zh-CN", "ko", "es", "fr", "de", "ru", "pt", "it" };

    private static Module add(String name, String category, boolean def) {
        Module m = new Module(name, category, def);
        ALL.add(m);
        return m;
    }

    // size + opacity, shared by every HUD element
    private static Module common(Module m) {
        m.add(new Setting("scale", "s.scale", 1.0f, 0.5f, 2.0f, 0.05f));
        m.add(new Setting("opacity", "s.opacity", 100, 20, 100, 5));
        return m;
    }

    public static Module get(String name) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).name.equals(name)) return ALL.get(i);
        }
        return null;
    }

    public static boolean on(String name) {
        for (int i = 0; i < ALL.size(); i++) {
            Module m = ALL.get(i);
            if (m.name.equals(name)) return m.enabled;
        }
        return false;
    }

    public static List<String> categories() {
        List<String> cs = new ArrayList<String>();
        for (int i = 0; i < ALL.size(); i++) {
            String c = ALL.get(i).category;
            if (!cs.contains(c)) cs.add(c);
        }
        return cs;
    }

    public static List<Module> byCategory(String category) {
        List<Module> r = new ArrayList<Module>();
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).category.equals(category)) r.add(ALL.get(i));
        }
        return r;
    }
}
