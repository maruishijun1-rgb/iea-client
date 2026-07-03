package dev.iea.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import dev.iea.client.module.Module;
import dev.iea.client.module.Modules;
import dev.iea.client.render.Font;
import dev.iea.client.render.Gl;

/**
 * Bedwars teammate-death title. Teammates are tracked from Hypixel PARTY chat (party = your
 * team) plus the same-nametag-colour scoreboard team (random fill). A death fires when a
 * teammate's name appears in chat AND that teammate then disappears from the tab list.
 */
public final class DeathAlert {
    private DeathAlert() { }

    private static long lastTickMs = 0;
    private static long lastDbgMs = 0;

    // current party member usernames (lower-case); your team. Built from party chat events.
    private static final Set<String> party = new HashSet<String>();

    // last computed teammate set (lower-case), shared with the Hitbox module so it can
    // colour enemies vs teammates the same way. Updated by tick() while on Hypixel.
    private static volatile Set<String> matesLc = new HashSet<String>();

    // Death rule = "a teammate's name appeared in chat AND that teammate has left the tab list".
    // mentionMs: teammate (lower-case) -> last time their name was seen in chat.
    // mentionName: teammate (lower-case) -> the original-case name to show in the title.
    // tabSeen: teammates we have actually seen in the tab list (so a name that was never in the
    //          game can't count as "disappeared").
    private static final Map<String, Long> mentionMs = new HashMap<String, Long>();
    private static final Map<String, String> mentionName = new HashMap<String, String>();
    private static final Set<String> tabSeen = new HashSet<String>();
    private static final long MENTION_WINDOW_MS = 5000; // chat mention must be recent

    /** True if `name` is currently one of your teammates (party or same-nametag-colour). */
    public static boolean isMate(String name) {
        return name != null && matesLc.contains(name.toLowerCase());
    }

    // active title
    private static String text = null;
    private static long showStart = 0, showUntil = 0;
    // de-dupe repeated lines for the same player
    private static String lastName = null;
    private static long lastFireMs = 0;

    /** Per-frame (throttled): poll chat, track the party, and arm the title on a teammate kill. */
    public static void tick() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastTickMs < 200) return; // chat persists; no need to poll every frame
            lastTickMs = now;

            Module m = Modules.get("DeathAlert");
            boolean alert = m != null && m.enabled;
            // Hitbox also needs the teammate set (enemy/teammate colours), so run the party
            // tracking whenever either feature is on — not only when DeathAlert is enabled.
            Module hbm = Modules.get("Hitbox");
            boolean hbOn = hbm != null && hbm.enabled;
            if (!alert && !hbOn) return;
            boolean debug = alert && m.bool("debug");

            List<String> lines = Mc.newChatLines(); // consume once (advances the marker)

            // 1) update party membership from any party chat events
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i);
                if (s != null && !(s = s.trim()).isEmpty()) updateParty(s, debug);
            }

            if (debug) Mc.dumpTeamInfo(); // confirm team structure in a real match

            if (!Mc.onHypixel()) { dbg(debug, now, "not on Hypixel"); return; }

            String me = Mc.localPlayerName();
            String meLc = me == null ? null : me.toLowerCase();

            // current tab-list players (lower-case) — used to spot a teammate disappearing
            Set<String> tabLc = new HashSet<String>();
            for (String n : Mc.tabListNames()) if (n != null) tabLc.add(n.toLowerCase());
            boolean inMatch = tabLc.size() >= 2 && tabLc.size() <= 16;

            // teammates = party members (premade) ∪ same-nametag-colour team (random fill).
            // The colour team is only trusted inside a match (player count 2..16) so the lobby's
            // shared rank colours don't group everyone.
            Set<String> mates = new HashSet<String>(party);
            if (inMatch) for (String n : Mc.teammates()) if (n != null) mates.add(n.toLowerCase());
            if (meLc != null) mates.remove(meLc);
            matesLc = mates; // publish for the Hitbox module

            // remember which teammates we have actually seen in the tab list, so a name that was
            // never in this game can't later count as "disappeared from the tab".
            for (String mate : mates) if (tabLc.contains(mate)) tabSeen.add(mate);

            dbg(debug, now, "me=" + me + " mates=" + mates + " party=" + party.size()
                    + " tab=" + tabLc.size());

            if (!alert) return; // Hitbox only needed the teammate set; no title to fire

            // RULE: a teammate's name appears in chat AND that teammate leaves the tab list.
            // 1) record any chat line that mentions a current teammate (keep the original-case name)
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i);
                if (s == null || (s = s.trim()).isEmpty()) continue;
                if (debug) System.out.println("[IEA][death] chat: " + s);
                for (String mate : mates) {
                    String disp = nameInLine(s, mate);
                    if (disp != null) {
                        mentionMs.put(mate, now);
                        mentionName.put(mate, disp);
                        if (debug) System.out.println("[IEA][death]   mention " + disp);
                    }
                }
            }
            // 2) fire when a recently-mentioned teammate (that was in the tab) is now gone from it
            Iterator<Map.Entry<String, Long>> it = mentionMs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                String mate = e.getKey();
                if (now - e.getValue() > MENTION_WINDOW_MS) { it.remove(); mentionName.remove(mate); continue; }
                if (tabSeen.contains(mate) && !tabLc.contains(mate)) {
                    String disp = mentionName.get(mate);
                    if (debug) System.out.println("[IEA][death]   -> DEATH " + disp + " (left tab)");
                    fire(disp != null ? disp : mate, m, now);
                    it.remove(); mentionName.remove(mate);
                    tabSeen.remove(mate); // re-arm only after they reappear in the tab
                }
            }
        } catch (Throwable ignored) { }
    }

    // the original-case name token in s whose lower-case equals nameLc (matched as a whole
    // token, after stripping rank brackets / punctuation), or null.
    private static String nameInLine(String s, String nameLc) {
        String[] tok = s.split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            String w = nameToken(tok[i]);
            if (!w.isEmpty() && w.toLowerCase().equals(nameLc)) return w;
        }
        return null;
    }

    // Parse Hypixel party chat to keep the `party` set current (names stored lower-case, no self).
    private static void updateParty(String s, boolean debug) {
        String low = s.toLowerCase();
        // leaving / disbanding clears the whole party
        if (low.contains("the party was disbanded") || low.contains("disbanded the party")
                || low.contains("you left the party") || low.contains("you have left the party")
                || low.contains("you are not currently in a party")
                || low.contains("you have been removed from the party")
                || low.contains("you were removed from your party")) {
            if (!party.isEmpty() && debug) System.out.println("[IEA][death] party cleared");
            party.clear();
            return;
        }
        // "You have joined [RANK] NAME's party!"  -> NAME is your party leader
        int ap = low.indexOf("'s party");
        if (ap > 0 && low.contains("joined")) {
            String name = lastNameToken(s.substring(0, ap));
            if (name != null) { party.clear(); party.add(name.toLowerCase());
                if (debug) System.out.println("[IEA][death] party joined " + name); }
            return;
        }
        // "Party Leader/Members/Moderators: [RANK] NAME ●  [RANK] NAME ●" (from /party list)
        if (low.startsWith("party leader") || low.startsWith("party members")
                || low.startsWith("party moderators")) {
            int c = s.indexOf(':');
            if (c >= 0) addRosterNames(s.substring(c + 1));
            return;
        }
        // "[RANK] NAME joined the party." -> add
        if (low.contains("joined the party")) {
            String name = firstNameToken(s);
            if (name != null) { party.add(name.toLowerCase());
                if (debug) System.out.println("[IEA][death] party +" + name); }
            return;
        }
        // "[RANK] NAME has left / been removed / been kicked" -> remove
        if (low.contains("left the party") || low.contains("has been removed from the party")
                || low.contains("was removed from the party") || low.contains("has been kicked")) {
            String name = firstNameToken(s);
            if (name != null) party.remove(name.toLowerCase());
        }
        // note: "disconnected" lines keep the member (they may rejoin)
    }

    // add every player-looking token from a roster fragment (skips [RANK] brackets and bullets)
    private static void addRosterNames(String frag) {
        String[] tok = frag.split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].startsWith("[")) continue;      // rank tag
            String w = nameToken(tok[i]);
            if (w.length() >= 3) party.add(w.toLowerCase());
        }
    }

    private static void dbg(boolean on, long now, String msg) {
        if (!on || now - lastDbgMs < 3000) return;
        lastDbgMs = now;
        System.out.println("[IEA][death] status " + msg);
    }

    // first whitespace token that is a real name (skips a leading [RANK] / symbol)
    private static String firstNameToken(String s) {
        String[] tok = s.split("\\s+");
        for (int i = 0; i < tok.length; i++) {
            if (tok[i].startsWith("[")) continue;
            String w = nameToken(tok[i]);
            if (w.length() >= 3) return w;
        }
        return null;
    }

    // last real-name token in the fragment (the username sits after any [RANK] prefix)
    private static String lastNameToken(String s) {
        String[] tok = s.split("\\s+");
        for (int i = tok.length - 1; i >= 0; i--) {
            String w = nameToken(tok[i]);
            if (w.length() >= 3) return w;
        }
        return null;
    }

    // trim leading/trailing non-name chars (☠, brackets, periods)
    private static String nameToken(String s) {
        int a = 0, b = s.length();
        while (a < b && !isNameChar(s.charAt(a))) a++;
        while (b > a && !isNameChar(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static void fire(String name, Module m, long now) {
        if (name.equalsIgnoreCase(lastName) && now - lastFireMs < 1500) return; // de-dupe
        lastName = name; lastFireMs = now;
        text = name + Lang.t("death_down");
        showStart = now;
        showUntil = now + (long) (m.num("seconds", 3f) * 1000f);
    }

    /** Draw the active title (call inside the Surface pass, after the HUD). */
    public static void render(Font font, int w, int h) {
        if (text == null || font == null) return;
        long now = System.currentTimeMillis();
        if (now >= showUntil) { text = null; return; }
        Module m = Modules.get("DeathAlert");
        if (m == null || !m.enabled) { text = null; return; }

        float total = Math.max(1f, showUntil - showStart);
        float t = (now - showStart) / total; // 0..1
        float a = 1f;                          // fade in (first 15%) / out (last 30%)
        if (t < 0.15f) a = t / 0.15f;
        else if (t > 0.7f) a = (1f - t) / 0.3f;
        if (a < 0f) a = 0f; else if (a > 1f) a = 1f;

        float scale = m.num("scale", 2.2f);
        int col = 0xFF000000
                | ((int) m.num("red", 255f) << 16)
                | ((int) m.num("green", 80f) << 8)
                | (int) m.num("blue", 80f);

        float prev = Gl.alpha;
        Gl.alpha = a;
        GL11.glPushMatrix();
        GL11.glTranslatef(w / 2f, h * 0.28f, 0f);
        GL11.glScalef(scale, scale, 1f);
        font.drawCentered(text, 0.6f, 0.6f, 0xFF101010); // drop shadow
        font.drawCentered(text, 0f, 0f, col);
        GL11.glPopMatrix();
        Gl.alpha = prev;
    }
}
