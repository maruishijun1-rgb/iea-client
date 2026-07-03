package dev.iea.client;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel "LevelHead": look up a player's Hypixel network level from the public API
 * and cache it, so {@link dev.iea.client.Hook} can draw it above their head.
 *
 * The Hypixel API needs a per-user key (create one at developer.hypixel.net). Put it
 * on the first line of "iea-hypixel-key.txt" in the game folder (the working dir).
 * Without a key the feature stays silent. Lookups are async + cached so rendering
 * never blocks on the network.
 */
public final class LevelHead {
    public static final int PENDING = -1; // fetch in flight
    public static final int NONE = -2;    // no key / error / no data

    private static String apiKey;
    private static boolean keyLoaded;
    private static long keyCheckedAt;

    // value kinds (index into the cached array)
    public static final int NETWORK = 0, BW_STAR = 1, SW_LEVEL = 2, BW_FKDR = 3, BW_WINS = 4, COUNT = 5;

    // uuid -> [network, bedwarsStar, skywarsLevel, bedwarsFKDR, bedwarsWins]; PENDING / NONE sentinels.
    private static final ConcurrentHashMap<String, float[]> cache = new ConcurrentHashMap<String, float[]>();
    private static final Pattern EXP = Pattern.compile("\"networkExp\"\\s*:\\s*([0-9.eE+]+)");
    private static final Pattern BW = Pattern.compile("\"bedwars_level\"\\s*:\\s*([0-9]+)");
    private static final Pattern SW = Pattern.compile("\"skywars_you_re_a_star\"\\s*:\\s*([0-9]+)");
    private static final Pattern FK = Pattern.compile("\"final_kills_bedwars\"\\s*:\\s*([0-9]+)");
    private static final Pattern FD = Pattern.compile("\"final_deaths_bedwars\"\\s*:\\s*([0-9]+)");
    private static final Pattern WINS = Pattern.compile("\"wins_bedwars\"\\s*:\\s*([0-9]+)");
    private static final Pattern CAUSE = Pattern.compile("\"cause\"\\s*:\\s*\"([^\"]*)\"");
    private static boolean okLogged; // log the first successful lookup once
    private static final ExecutorService pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "IEA-LevelHead");
            t.setDaemon(true);
            return t;
        }
    });

    private static void loadKey() {
        long now = System.currentTimeMillis();
        // re-check the file occasionally so the user can drop the key in without a restart
        if (keyLoaded && now - keyCheckedAt < 10000) return;
        keyCheckedAt = now;
        try {
            File f = new File("iea-hypixel-key.txt"); // game working directory
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                try {
                    String s = r.readLine();
                    String k = (s == null) ? "" : s.trim();
                    if (!k.equals(apiKey)) {
                        apiKey = k;
                        System.out.println("[IEA] LevelHead API key loaded.");
                    }
                } finally { r.close(); }
            } else if (!keyLoaded) {
                System.out.println("[IEA] LevelHead: no key. Put your Hypixel API key in "
                        + f.getAbsolutePath());
            }
        } catch (Throwable ignored) { }
        keyLoaded = true;
    }

    /** Formatted value of the given kind for an (undashed) UUID, or null (pending / none / no key). */
    public static String value(String uuid, int type) {
        loadKey();
        if (apiKey == null || apiKey.isEmpty()) return null;
        float[] v = cache.get(uuid);
        if (v == null) {
            cache.put(uuid, fill(PENDING));
            final String u = uuid;
            pool.submit(new Runnable() { public void run() { fetch(u); } });
            return null;
        }
        if (v[0] == PENDING || type < 0 || type >= COUNT) return null;
        float val = v[type];
        if (val == NONE) return null;
        return (type == BW_FKDR) ? String.format("%.2f", val) : String.valueOf((int) val);
    }

    private static float[] fill(int sentinel) {
        float[] a = new float[COUNT];
        for (int i = 0; i < COUNT; i++) a[i] = sentinel;
        return a;
    }

    private static void fetch(String uuid) {
        try {
            // Modern Hypixel API: key goes in the "API-Key" HEADER (the old "?key=" query
            // parameter is rejected with 403), and lookups use the v2 endpoint.
            URL url = new URL("https://api.hypixel.net/v2/player?uuid=" + uuid);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("API-Key", apiKey);
            c.setRequestProperty("User-Agent", "IEA-Client");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String body = read(in);
            if (code != 200) {
                Matcher mc = CAUSE.matcher(body);
                String cause = mc.find() ? mc.group(1) : "";
                System.out.println("[IEA] LevelHead: API HTTP " + code
                        + (cause.isEmpty() ? "" : " - " + cause)
                        + (code == 403 ? " (check your API key)" : code == 429 ? " (rate limited)" : ""));
                cache.put(uuid, fill(NONE));
                return;
            }
            float[] r = fill(NONE);
            Matcher m = EXP.matcher(body);
            if (m.find()) {
                double exp = Double.parseDouble(m.group(1));
                int lvl = (int) (Math.sqrt(12.25 + 0.0008 * exp) - 2.5); // official Hypixel formula
                r[NETWORK] = lvl < 1 ? 1 : lvl;
            }
            r[BW_STAR] = num(BW, body, r[BW_STAR]);     // achievements.bedwars_level
            r[SW_LEVEL] = num(SW, body, r[SW_LEVEL]);   // achievements.skywars_you_re_a_star
            r[BW_WINS] = num(WINS, body, r[BW_WINS]);   // stats.Bedwars.wins_bedwars
            float fk = num(FK, body, -1), fd = num(FD, body, -1);
            if (fk >= 0) r[BW_FKDR] = (fd > 0) ? (fk / fd) : fk; // final kills / final deaths
            cache.put(uuid, r);
            if (!okLogged) { okLogged = true; System.out.println("[IEA] LevelHead: API OK"); }
        } catch (Throwable t) {
            System.out.println("[IEA] LevelHead: request failed: " + t);
            cache.put(uuid, fill(NONE));
        }
    }

    private static float num(Pattern p, String body, float def) {
        Matcher m = p.matcher(body);
        return m.find() ? Float.parseFloat(m.group(1)) : def;
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Exception e) { }
        }
    }
}
