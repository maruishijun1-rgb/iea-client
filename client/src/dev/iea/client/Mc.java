package dev.iea.client;

import java.io.File;
import java.io.FileWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import dev.iea.agent.Agent;

/**
 * Runtime mapping bridge. Minecraft 1.8.9 is obfuscated (classes live in the
 * default package with short names), so instead of hard-coding fragile Notch
 * names we DISCOVER the Minecraft singleton and GameSettings at runtime and dump
 * their fields to the log. Use that dump to pin exact field names for features
 * like Fullbright (gamma) and Zoom (fov).
 */
public final class Mc {
    public static Object minecraft;       // the Minecraft instance
    public static Object gameSettings;    // the GameSettings instance
    public static Class<?> gameSettingsClass;

    // KeyBinding for sprint (found via the un-obfuscated "key.sprint" string)
    public static Object keyBindSprint;
    private static Field kbPressedField;

    private static boolean done = false;
    private static int attempts = 0;

    public static boolean ready() { return gameSettings != null; }

    // --- self-nametag pitch fix --------------------------------------------------------------
    // Vanilla's renderLivingLabel billboards with RenderManager.playerViewX (camera pitch); in
    // the front 3rd-person view the camera-entity's own label comes out pitch-inverted. We
    // negate playerViewX only while the local player's label draws, then restore it. We can't
    // name the obf fields, so identify them by behaviour (sampled each frame in observeView()):
    //   thirdPersonView = the int GameSettings field that cycles 0/1/2 (only F5 changes that)
    //   playerViewX     = the RenderManager float that stays within ±90 (pitch); yaw exceeds it
    private static Object renderMgr;
    private static Field pitchField;
    private static List<Field> rmFloats;
    private static float[] rmFloatMaxAbs;
    private static boolean rmSearched;
    private static Field tpvField;
    private static List<Field> tpvCands;
    private static int[] tpvMask;

    public static void observeView() {
        // identify thirdPersonView
        if (tpvField == null && gameSettings != null && gameSettingsClass != null) {
            try {
                if (tpvCands == null) {
                    tpvCands = new ArrayList<Field>();
                    for (Field f : gameSettingsClass.getDeclaredFields())
                        if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) { f.setAccessible(true); tpvCands.add(f); }
                    tpvMask = new int[tpvCands.size()];
                }
                for (int i = 0; i < tpvCands.size(); i++) {
                    int v; try { v = tpvCands.get(i).getInt(gameSettings); } catch (Throwable t) { continue; }
                    if (v < 0 || v > 2) continue;
                    tpvMask[i] |= (1 << v);
                    if ((tpvMask[i] & 1) != 0 && (tpvMask[i] & 6) != 0) {
                        tpvField = tpvCands.get(i);
                        System.out.println("[IEA] thirdPersonView field = " + tpvField.getName());
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        // find the RenderManager + identify its pitch float (retry each frame until found —
        // it doesn't exist on Minecraft yet during early init).
        if (renderMgr == null && minecraft != null) {
            try {
                for (Field f : minecraft.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                    f.setAccessible(true);
                    Object o; try { o = f.get(minecraft); } catch (Throwable e) { continue; }
                    if (o == null) continue;
                    int floats = 0; boolean map = false;
                    for (Field g : o.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(g.getModifiers())) continue;
                        if (g.getType() == float.class) floats++;
                        else if (Map.class.isAssignableFrom(g.getType())) map = true;
                    }
                    if (floats == 2 && map) { // RenderManager: playerViewX/Y + render maps
                        renderMgr = o;
                        rmFloats = new ArrayList<Field>();
                        for (Field g : o.getClass().getDeclaredFields())
                            if (!Modifier.isStatic(g.getModifiers()) && g.getType() == float.class) { g.setAccessible(true); rmFloats.add(g); }
                        rmFloatMaxAbs = new float[rmFloats.size()];
                        System.out.println("[IEA] RenderManager = " + o.getClass().getName());
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        if (renderMgr != null && pitchField == null && rmFloats != null) {
            try {
                int over = -1, under = -1, unders = 0;
                for (int i = 0; i < rmFloats.size(); i++) {
                    float a = Math.abs(rmFloats.get(i).getFloat(renderMgr));
                    if (a > rmFloatMaxAbs[i]) rmFloatMaxAbs[i] = a;
                    if (rmFloatMaxAbs[i] > 91f) over = i; else { under = i; unders++; }
                }
                if (over >= 0 && unders == 1) {
                    pitchField = rmFloats.get(under);
                    System.out.println("[IEA] playerViewX (pitch) = " + pitchField.getName());
                }
            } catch (Throwable ignored) { }
        }
    }

    public static int thirdPersonView() {
        if (tpvField == null || gameSettings == null) return -1;
        try { return tpvField.getInt(gameSettings); } catch (Throwable t) { return -1; }
    }

    /** Negate the nametag billboard's camera pitch (involution: call again to restore). */
    public static boolean negateNametagPitch() {
        if (pitchField == null || renderMgr == null) return false;
        try { pitchField.setFloat(renderMgr, -pitchField.getFloat(renderMgr)); return true; }
        catch (Throwable t) { return false; }
    }

    public static void tryDiscover() {
        if (done || attempts > 40) return;
        attempts++;
        try {
            Instrumentation in = Agent.inst;
            if (in == null) return;

            Class<?> mcClass = null;
            Object mcInst = null;
            int bestFields = -1;

            for (Class<?> c : in.getAllLoadedClasses()) {
                if (c == null) continue;
                String n = c.getName();
                if (n.indexOf('.') >= 0) continue;        // only default-package (obfuscated) classes
                if (c.isInterface() || c.isArray() || c.isEnum()) continue;
                try {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) && f.getType() == c) { // singleton self-field
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val != null) {
                                int fc = c.getDeclaredFields().length;
                                if (fc > bestFields) { bestFields = fc; mcClass = c; mcInst = val; }
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }

            if (mcInst == null) return; // not ready yet, retry next time
            minecraft = mcInst;
            System.out.println("[IEA][map] Minecraft class = " + mcClass.getName() + " (" + bestFields + " fields)");

            // GameSettings = the field-object (default package) with the most float fields
            Object gs = null; Class<?> gsClass = null; int bestFloats = -1;
            for (Field f : mcClass.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(mcInst);
                    if (v == null) continue;
                    Class<?> vc = v.getClass();
                    if (vc.getName().indexOf('.') >= 0) continue;
                    int floats = 0;
                    for (Field ff : vc.getDeclaredFields()) if (ff.getType() == float.class) floats++;
                    if (floats > bestFloats) { bestFloats = floats; gs = v; gsClass = vc; }
                } catch (Throwable ignored) { }
            }

            if (gs == null) { done = true; return; }
            gameSettings = gs;
            gameSettingsClass = gsClass;
            System.out.println("[IEA][map] GameSettings class = " + gsClass.getName() + " (" + bestFloats + " floats)");
            for (Field ff : gsClass.getDeclaredFields()) {
                if (ff.getType() == float.class) {
                    ff.setAccessible(true);
                    System.out.println("[IEA][map]   float " + ff.getName() + " = " + ff.getFloat(gs));
                }
            }
            discoverSprint();
            System.out.println("[IEA][map] discovery complete. Send these lines to pin gamma/fov.");
            System.out.println("[IEA][map] enter a world, look at a block, then press F6 to write the render dump.");
            done = true;
        } catch (Throwable t) {
            System.out.println("[IEA][map] discovery error: " + t);
        }
    }

    // --- ToggleSprint: find keyBindSprint, then drive its "pressed" flag ---
    // KeyBinding.keyDescription is the literal "key.sprint" (string constants are
    // NOT obfuscated), so we can pin it reliably across the obfuscated jar.
    private static void discoverSprint() {
        try {
            for (Field f : gameSettingsClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try { v = f.get(gameSettings); } catch (Throwable t) { continue; }
                if (v == null) continue;
                if (v.getClass().isArray()) {
                    int len = Array.getLength(v);
                    for (int i = 0; i < len; i++) {
                        Object el = Array.get(v, i);
                        if (isSprintBinding(el)) { bindSprint(el); return; }
                    }
                } else if (isSprintBinding(v)) { bindSprint(v); return; }
            }
            System.out.println("[IEA][map] keyBindSprint not found (ToggleSprint will be inactive)");
        } catch (Throwable ignored) { }
    }

    private static boolean isSprintBinding(Object o) {
        if (o == null) return false;
        Class<?> c = o.getClass();
        if (c.getName().indexOf('.') >= 0) return false; // obfuscated KeyBinding is default-package
        try {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    if ("key.sprint".equals(f.get(o))) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void bindSprint(Object kb) {
        keyBindSprint = kb;
        try {
            for (Field f : kb.getClass().getDeclaredFields()) { // KeyBinding has one boolean: pressed
                if (f.getType() == boolean.class) { f.setAccessible(true); kbPressedField = f; break; }
            }
        } catch (Throwable ignored) { }
        System.out.println("[IEA][map] keyBindSprint = " + kb.getClass().getName()
                + " pressed=" + (kbPressedField != null ? kbPressedField.getName() : "?"));
    }

    public static boolean sprintReady() { return keyBindSprint != null && kbPressedField != null; }

    // AxisAlignedBB (obf aug) holds 6 doubles a,b,c,d,e,f = minX,minY,minZ,maxX,maxY,maxZ.
    private static Field[] aabbFields;
    public static Class<?> aabbClass;
    public static double[] aabb(Object box) {
        if (box == null) return null;
        try {
            if (aabbFields == null) {
                List<Field> fs = new ArrayList<Field>();
                for (Field f : box.getClass().getDeclaredFields()) {
                    if (f.getType() == double.class) { f.setAccessible(true); fs.add(f); }
                }
                if (fs.size() < 6) return null;
                aabbFields = new Field[] { fs.get(0), fs.get(1), fs.get(2), fs.get(3), fs.get(4), fs.get(5) };
                aabbClass = box.getClass();
            }
            double[] r = new double[6];
            for (int i = 0; i < 6; i++) r[i] = aabbFields[i].getDouble(box);
            return r;
        } catch (Throwable t) { return null; }
    }

    // Entity.boundingBox (obf Entity=pk, field f:aug). Self-bootstrapping: the
    // base-most field whose type is an obf class with exactly 6 double fields
    // (= AxisAlignedBB). Works even if block overlay never ran.
    private static Field entityBoxField;
    public static Object entityBox(Object entity) {
        if (entity == null) return null;
        try {
            if (entityBoxField == null) {
                Field found = null;
                for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        if (ft.isPrimitive() || ft.isArray() || ft.getName().indexOf('.') >= 0) continue;
                        // AxisAlignedBB is uniquely "exactly 6 fields, all double"
                        if (isAabb(ft)) { f.setAccessible(true); found = f; aabbClass = ft; }
                    }
                }
                if (found == null) { System.out.println("[IEA] hitbox: boundingBox field not found"); return null; }
                entityBoxField = found;
                System.out.println("[IEA] hitbox: boundingBox = " + found.getDeclaringClass().getName()
                        + "." + found.getName() + " (" + aabbClass.getName() + ")");
            }
            return entityBoxField.get(entity);
        } catch (Throwable t) { return null; }
    }

    // ItemPhysics: an EntityItem's spin/bob is driven by its int "age" + float "hoverStart"
    // fields. Zeroing all of its own int/float fields just before vanilla renders (and
    // restoring straight after) freezes the item so it rests flat and still. We touch only
    // fields declared on the concrete EntityItem class, and restore them the same frame.
    private static Field[] itemMotionFields;
    private static Class<?> itemMotionClass;
    public static float[] saveAndZeroItemMotion(Object item) {
        if (item == null) return null;
        try {
            Class<?> c = item.getClass();
            if (itemMotionFields == null || itemMotionClass != c) {
                List<Field> fs = new ArrayList<Field>();
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t == int.class || t == float.class) { f.setAccessible(true); fs.add(f); }
                }
                itemMotionFields = fs.toArray(new Field[0]);
                itemMotionClass = c;
            }
            float[] saved = new float[itemMotionFields.length];
            for (int i = 0; i < itemMotionFields.length; i++) {
                Field f = itemMotionFields[i];
                if (f.getType() == int.class) { saved[i] = f.getInt(item); f.setInt(item, 0); }
                else { saved[i] = f.getFloat(item); f.setFloat(item, 0f); }
            }
            return saved;
        } catch (Throwable t) { return null; }
    }
    public static void restoreItemMotion(Object item, float[] saved) {
        if (item == null || saved == null || itemMotionFields == null) return;
        try {
            for (int i = 0; i < itemMotionFields.length && i < saved.length; i++) {
                Field f = itemMotionFields[i];
                if (f.getType() == int.class) f.setInt(item, (int) saved[i]);
                else f.setFloat(item, saved[i]);
            }
        } catch (Throwable ignored) { }
    }

    // Entity invisibility = DataWatcher flags byte (index 0) bit 0x20. Avoids drawing
    // hitboxes for invisible entities (anti-cheat / fairness). DataWatcher is the
    // entity field whose type has a (int)->byte getter and a Map field.
    private static Field dataWatcherField;
    private static Method dwByteGetter;
    private static boolean dwSearched;
    public static boolean isInvisible(Object entity) {
        if (entity == null) return false;
        try {
            if (!dwSearched) {
                dwSearched = true;
                outer:
                for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        if (ft.isPrimitive() || ft.isArray() || ft.getName().indexOf('.') >= 0) continue;
                        if (!hasMapField(ft)) continue;
                        Method bg = byteGetter(ft);
                        if (bg != null) { f.setAccessible(true); dataWatcherField = f; dwByteGetter = bg; break outer; }
                    }
                }
            }
            if (dataWatcherField == null || dwByteGetter == null) return false;
            Object dw = dataWatcherField.get(entity);
            if (dw == null) return false;
            byte flags = ((Byte) dwByteGetter.invoke(dw, Integer.valueOf(0))).byteValue();
            return (flags & 0x20) != 0;
        } catch (Throwable t) { return false; }
    }

    // TNT timer: find EntityTNTPrimed via EntityList's (un-obfuscated) "PrimedTnt"
    // registry string, then read its single int field (fuse).
    public static Class<?> tntClass;
    private static Field tntFuseField;
    private static int tntAttempts = 0;
    public static int tntFuse(Object entity) {
        if (entity == null) return -1;
        try {
            if (tntClass == null && tntAttempts < 200) { tntAttempts++; findTnt(); }
            if (tntClass == null || tntFuseField == null) return -1;
            if (!tntClass.isInstance(entity)) return -1;
            return tntFuseField.getInt(entity);
        } catch (Throwable t) { return -1; }
    }

    private static boolean tntFailLogged = false;
    private static void findTnt() {
        Instrumentation in = Agent.inst;
        if (in == null) return;
        for (Class<?> c : in.getAllLoadedClasses()) {
            if (c == null || c.getName().indexOf('.') >= 0) continue;
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map == null) continue;
                    Class<?> found = tntFromMap(map);
                    if (found != null) { tntClass = found; break; }
                } catch (Throwable ignored) { }
            }
            if (tntClass != null) break;
        }
        if (tntClass == null) {
            if (tntAttempts >= 199 && !tntFailLogged) {
                tntFailLogged = true;
                System.out.println("[IEA] TNT: 'PrimedTnt' not found in EntityList maps");
            }
            return;
        }
        for (Field f : tntClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                f.setAccessible(true); tntFuseField = f; break;
            }
        }
        System.out.println("[IEA] TNT = " + tntClass.getName()
                + " fuse=" + (tntFuseField != null ? tntFuseField.getName() : "?"));
    }

    private static Class<?> tntFromMap(Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey(), v = e.getValue();
            if ("PrimedTnt".equals(k) && v instanceof Class) return (Class<?>) v;
            if ("PrimedTnt".equals(v) && k instanceof Class) return (Class<?>) k;
        }
        return null;
    }

    private static boolean hasMapField(Class<?> c) {
        for (Field f : c.getDeclaredFields()) if (java.util.Map.class.isAssignableFrom(f.getType())) return true;
        return false;
    }

    private static Method byteGetter(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == byte.class && p.length == 1 && p[0] == int.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // declared instance fields (statics removed) in declaration order
    private static List<Field> instanceFields(Class<?> c) {
        List<Field> r = new ArrayList<Field>();
        for (Field f : c.getDeclaredFields())
            if (!Modifier.isStatic(f.getModifiers())) r.add(f);
        return r;
    }

    // AxisAlignedBB has exactly 6 instance fields and they are ALL double.
    private static boolean isAabb(Class<?> c) {
        int total = 0, doubles = 0;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            total++;
            if (f.getType() == double.class) doubles++;
        }
        return total == 6 && doubles == 6;
    }

    public static void setSprint(boolean down) {
        if (keyBindSprint == null || kbPressedField == null) return;
        try { kbPressedField.setBoolean(keyBindSprint, down); } catch (Throwable ignored) { }
    }

    // Release every key binding (vanilla does this when a GUI opens). Without it, a
    // key/mouse-side-button held when our menu opens never gets its release event
    // (we drain the queues) and stays stuck pressed.
    public static void unpressAllKeys() {
        if (gameSettings == null || keyBindSprint == null || kbPressedField == null) return;
        Class<?> kbClass = keyBindSprint.getClass();
        for (Field f : gameSettingsClass.getDeclaredFields()) {
            try {
                f.setAccessible(true);
                if (f.getType().isArray() && f.getType().getComponentType() == kbClass) {
                    Object arr = f.get(gameSettings);
                    if (arr == null) continue;
                    for (int i = 0; i < Array.getLength(arr); i++) {
                        Object kb = Array.get(arr, i);
                        if (kb != null) kbPressedField.setBoolean(kb, false);
                    }
                } else if (f.getType() == kbClass) {
                    Object kb = f.get(gameSettings);
                    if (kb != null) kbPressedField.setBoolean(kb, false);
                }
            } catch (Throwable ignored) { }
        }
    }

    // --- OldAnimations: ItemRenderer (this jar: bfn) ----------------------
    // 1.7-style "no item-switch dip": force equippedProgress to 1 each frame so
    // the held item never lowers/raises when you change slots.
    // ItemRenderer is found structurally as a Minecraft-field object whose class:
    //   * has EXACTLY 2 float instance fields (equippedProgress/prevEquippedProgress)
    //   * has at least one int (equippedItemSlot)
    //   * holds a back-reference of Minecraft's type
    //   * declares a (float)->void method (renderItemInFirstPerson)
    // The last test is what excludes PlayerControllerMP (bda), which also has
    // 2 floats + int + a Minecraft back-ref (its 2 floats are block-break progress!
    // forcing those to 1 was the bug that broke mining / world transitions).
    private static Object itemRenderer;
    public static Class<?> itemRendererClass;
    private static Field equipProgField, prevEquipProgField;
    private static int itemRendererAttempts = 0;
    private static boolean itemRendererFailLogged = false;

    public static boolean itemRendererReady() { return equipProgField != null; }

    public static void ensureItemRenderer() { discoverItemRenderer(); }

    private static void discoverItemRenderer() {
        if (equipProgField != null || minecraft == null || itemRendererAttempts > 200) return;
        itemRendererAttempts++;
        try {
            Class<?> mcClass = minecraft.getClass();
            for (Field f : mcClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray() || ft.getName().indexOf('.') >= 0) continue;
                int floats = 0, ints = 0; boolean backRef = false;
                for (Field ff : ft.getDeclaredFields()) {
                    if (Modifier.isStatic(ff.getModifiers())) continue;
                    Class<?> t = ff.getType();
                    if (t == float.class) floats++;
                    else if (t == int.class) ints++;
                    else if (t == mcClass) backRef = true;
                }
                if (floats != 2 || ints < 1 || !backRef) continue;
                if (!hasFloatVoidMethod(ft)) continue; // excludes PlayerControllerMP
                f.setAccessible(true);
                Object inst = f.get(minecraft);
                if (inst == null) continue;
                Field a = null, b = null;
                for (Field ff : ft.getDeclaredFields()) {
                    if (Modifier.isStatic(ff.getModifiers())) continue;
                    if (ff.getType() == float.class) {
                        ff.setAccessible(true);
                        if (a == null) a = ff; else b = ff;
                    }
                }
                itemRenderer = inst; itemRendererClass = ft; equipProgField = a; prevEquipProgField = b;
                System.out.println("[IEA] ItemRenderer = " + ft.getName()
                        + " equipProg=" + a.getName() + "," + b.getName());
                return;
            }
            if (itemRendererAttempts >= 200 && !itemRendererFailLogged) {
                itemRendererFailLogged = true;
                System.out.println("[IEA] ItemRenderer not found (OldAnimations inactive)");
            }
        } catch (Throwable t) {
            System.out.println("[IEA] ItemRenderer discovery error: " + t);
        }
    }

    private static boolean hasFloatVoidMethod(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 1 && p[0] == float.class) return true;
        }
        return false;
    }

    // "No item-switch dip": override equippedProgress to 1 ONLY during the draw, then
    // restore it. The tick's updateEquippedItem keeps running with the real value, so
    // the displayed item still swaps when you change slots (forcing it permanently
    // froze equippedProgress above the swap threshold and the held item never changed).
    private static float savedEquip, savedPrevEquip;
    public static boolean equipDrawEngage() {
        if (equipProgField == null) { discoverItemRenderer(); if (equipProgField == null) return false; }
        try {
            savedEquip = equipProgField.getFloat(itemRenderer);
            savedPrevEquip = prevEquipProgField.getFloat(itemRenderer);
            equipProgField.setFloat(itemRenderer, 1f);
            prevEquipProgField.setFloat(itemRenderer, 1f);
            return true;
        } catch (Throwable t) { return false; }
    }
    public static void equipDrawRestore() {
        if (equipProgField == null) return;
        try {
            equipProgField.setFloat(itemRenderer, savedEquip);
            prevEquipProgField.setFloat(itemRenderer, savedPrevEquip);
        } catch (Throwable ignored) { }
    }

    // --- OldAnimations: "swing while using an item" -----------------------
    // Vanilla 1.8 hides the swing animation while you eat / drink / block / draw a
    // bow, because ItemRenderer's first-person draw branches on "item in use count
    // > 0". We briefly force that count to 0 *only during the item draw* so vanilla
    // takes its own swing path (no animation math is reimplemented). We also kick
    // off the swing on a left-click while using, in case vanilla didn't.
    //
    // Field discovery is by STRUCTURE (obf letters repeat across classes, so we
    // can't fetch by name blindly):
    //   EntityLivingBase = the class in the player's hierarchy that declares an
    //     obfuscated ItemStack[] field (previousEquipment). isSwingInProgress is the
    //     first boolean declared after it; swingProgressInt the next int.
    //   EntityPlayer = the class declaring a scalar ItemStack field (itemInUse);
    //     itemInUseCount is the int declared right after it.
    private static Field playerField;            // Minecraft -> local player (value changes per world)
    private static boolean fieldsResolved;
    private static Field swingInProgressField, swingIntField, swingProgressField, useCountField;
    private static Field potionMapField;         // EntityLivingBase.activePotionsMap
    private static Field hurtTimeField;          // EntityLivingBase.hurtTime (NoHurtCam)
    private static Class<?> stackClassS;         // ItemStack (zx), from the equipment array

    private static Object thePlayer() {
        try {
            if (minecraft == null) return null;
            if (playerField == null) {
                for (Field f : minecraft.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.isArray() || ft.getName().indexOf('.') >= 0) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(minecraft); } catch (Throwable t) { continue; }
                    if (v != null && isLocalPlayer(v.getClass())) { playerField = f; break; }
                }
                if (playerField == null) return null;
            }
            return playerField.get(minecraft);
        } catch (Throwable t) { return null; }
    }

    // The local player = an EntityPlayer. We require ALL of (static fields skipped):
    //   * an Entity AABB field (a type whose only fields are exactly 6 doubles)  -> Entity
    //   * an obfuscated ItemStack[] field (previousEquipment)                    -> EntityLivingBase
    //   * a scalar field of that ItemStack type (itemInUse)                      -> EntityPlayer
    // The AABB requirement is what excludes GameSettings (avb[]) and FontRenderer,
    // which previously matched and made us pick the wrong object.
    private static boolean isLocalPlayer(Class<?> cls) {
        boolean aabb = false;
        Class<?> stack = null;
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isArray()) {
                    Class<?> comp = t.getComponentType();
                    if (!comp.isPrimitive() && comp.getName().indexOf('.') < 0) stack = comp;
                } else if (!t.isPrimitive() && t.getName().indexOf('.') < 0 && isAabb(t)) {
                    aabb = true;
                }
            }
        if (!aabb || stack == null) return false;
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == stack) return true;
            }
        return false;
    }

    private static boolean resolvePlayerFields(Object p) {
        if (fieldsResolved) return swingInProgressField != null && useCountField != null;
        fieldsResolved = true;
        try {
            Class<?> cls = p.getClass();
            Class<?> stackClass = null, livingClass = null;
            for (Class<?> c = cls; c != null; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t.isArray() && !t.getComponentType().isPrimitive()
                            && t.getComponentType().getName().indexOf('.') < 0) {
                        stackClass = t.getComponentType(); livingClass = c;
                    }
                }
            if (stackClass == null || livingClass == null) {
                System.out.println("[IEA] swing: ItemStack[] not found");
                return false;
            }
            stackClassS = stackClass; // kept for the inventory / armor lookups
            // swing fields: in EntityLivingBase, the first boolean declared after the
            // ItemStack[] array (isSwingInProgress), then the next int (swingProgressInt).
            List<Field> lf = instanceFields(livingClass);
            int ai = -1;
            for (int i = 0; i < lf.size(); i++)
                if (lf.get(i).getType().isArray() && lf.get(i).getType().getComponentType() == stackClass) { ai = i; break; }
            int swingIntIdx = -1;
            for (int i = ai + 1; i < lf.size(); i++) {
                Field f = lf.get(i);
                if (swingInProgressField == null && f.getType() == boolean.class) {
                    f.setAccessible(true); swingInProgressField = f; continue;
                }
                if (swingInProgressField != null && swingIntField == null && f.getType() == int.class) {
                    f.setAccessible(true); swingIntField = f; swingIntIdx = i; break;
                }
            }
            // swingProgress (float) = the 3rd float after swingProgressInt
            //   (1st=attackedAtYaw, 2nd=prevSwingProgress, 3rd=swingProgress).
            if (swingIntIdx >= 0) {
                int fc = 0;
                for (int i = swingIntIdx + 1; i < lf.size(); i++) {
                    if (lf.get(i).getType() == float.class) {
                        fc++;
                        if (fc == 3) { Field f = lf.get(i); f.setAccessible(true); swingProgressField = f; break; }
                    }
                }
            }
            // activePotionsMap: the only java.util.Map declared on EntityLivingBase
            for (int i = 0; i < lf.size(); i++)
                if (java.util.Map.class.isAssignableFrom(lf.get(i).getType())) {
                    lf.get(i).setAccessible(true); potionMapField = lf.get(i); break;
                }
            // hurtTime: ints after the ItemStack[] run swingProgressInt, arrowHitTimer,
            // hurtTime, maxHurtTime — so the 3rd int after the array is hurtTime.
            int ic = 0;
            for (int i = ai + 1; i < lf.size(); i++) {
                Field f = lf.get(i);
                if (f.getType() == int.class && ++ic == 3) { f.setAccessible(true); hurtTimeField = f; break; }
            }
            // itemInUseCount: in EntityPlayer, the int declared right after the scalar
            // ItemStack field (itemInUse).
            for (Class<?> c = cls; c != null && useCountField == null; c = c.getSuperclass()) {
                List<Field> pf = instanceFields(c);
                for (int i = 0; i < pf.size(); i++) {
                    if (pf.get(i).getType() == stackClass) {
                        for (int j = i + 1; j < pf.size(); j++)
                            if (pf.get(j).getType() == int.class) {
                                Field f = pf.get(j); f.setAccessible(true); useCountField = f; break;
                            }
                        break;
                    }
                }
            }
            System.out.println("[IEA] swing-while-use: living=" + livingClass.getName()
                    + " swing=" + (swingInProgressField != null ? swingInProgressField.getName() : "?")
                    + " swingProg=" + (swingProgressField != null ? swingProgressField.getName() : "?")
                    + " useCount=" + (useCountField != null ? useCountField.getName() : "?")
                    + " potions=" + (potionMapField != null ? potionMapField.getName() : "?")
                    + " hurtTime=" + (hurtTimeField != null ? hurtTimeField.getName() : "?"));
            return swingInProgressField != null && useCountField != null;
        } catch (Throwable t) {
            System.out.println("[IEA] swing field resolve error: " + t);
            return false;
        }
    }

    // True when the player is using an item (itemInUseCount > 0) — i.e. eating, drinking,
    // blocking with a sword, or drawing a bow. Used to gate the swing overlay so it only
    // applies on the use-pose render (and carries over the instant right-click begins).
    public static boolean isUsingItem() {
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p)) return false;
        try { return useCountField.getInt(p) > 0; } catch (Throwable t) { return false; }
    }

    // The local player object (for identity checks, e.g. smooth-sneak's eye-height
    // filter only applies to the local player's camera, never other entities).
    public static Object localPlayer() { return thePlayer(); }

    public static boolean isLocalPlayer(Object e) { return e != null && e == thePlayer(); }

    /** True while connected to a Hypixel server (LevelHead is Hypixel-only). */
    public static boolean onHypixel() {
        String a = serverAddress();
        return a != null && a.toLowerCase().contains("hypixel.net");
    }

    // --- LevelHead: read a player entity's GameProfile (UUID + name). GameProfile is
    // com.mojang.authlib.GameProfile (a LIBRARY class, NOT obfuscated), so we find the
    // entity's no-arg getter by return type and call getId()/getName() on it by name.
    private static Method gameProfileM;
    public static Object playerProfile(Object entity) {
        if (entity == null) return null;
        try {
            if (gameProfileM != null)
                return gameProfileM.getDeclaringClass().isInstance(entity) ? gameProfileM.invoke(entity) : null;
            for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0
                            && "com.mojang.authlib.GameProfile".equals(m.getReturnType().getName())) {
                        m.setAccessible(true);
                        gameProfileM = m;
                        return m.invoke(entity);
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null; // non-player entity (no GameProfile getter)
    }

    /** UUID of a GameProfile, dashes stripped (Hypixel API form), or null. */
    public static String profileId(Object profile) {
        if (profile == null) return null;
        try {
            Object id = profile.getClass().getMethod("getId").invoke(profile);
            return id == null ? null : id.toString().replace("-", "");
        } catch (Throwable t) { return null; }
    }

    public static String profileName(Object profile) {
        if (profile == null) return null;
        try { return (String) profile.getClass().getMethod("getName").invoke(profile); }
        catch (Throwable t) { return null; }
    }

    // NoHurtCam: hold hurtTime at 0 so EntityRenderer.hurtCameraEffect never shakes.
    public static void zeroHurtTime() {
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || hurtTimeField == null) return;
        try { if (hurtTimeField.getInt(p) != 0) hurtTimeField.setInt(p, 0); } catch (Throwable ignored) { }
    }

    // HitColor: is this entity currently flashing from damage? hurtTimeField was discovered
    // on EntityLivingBase (pr), so it reads for ANY living entity, not just the local player.
    public static boolean isHurt(Object entity) {
        if (entity == null) return false;
        try {
            if (hurtTimeField == null) { Object p = thePlayer(); if (p != null) resolvePlayerFields(p); }
            return hurtTimeField != null && hurtTimeField.getInt(entity) > 0;
        } catch (Throwable t) { return false; }
    }

    // SwingSpeed: read/drive the local player's swing counter (isSwingInProgress /
    // swingProgressInt, both discovered by resolvePlayerFields).
    public static boolean swingFieldsReady() {
        Object p = thePlayer();
        return p != null && resolvePlayerFields(p) && swingInProgressField != null && swingIntField != null;
    }
    public static boolean isSwinging() {
        Object p = thePlayer();
        try { return p != null && swingInProgressField != null && swingInProgressField.getBoolean(p); }
        catch (Throwable t) { return false; }
    }
    public static int swingInt() {
        Object p = thePlayer();
        try { return (p != null && swingIntField != null) ? swingIntField.getInt(p) : -1; }
        catch (Throwable t) { return -1; }
    }
    public static void setSwingInt(int v) {
        Object p = thePlayer();
        try { if (p != null && swingIntField != null) swingIntField.setInt(p, v); }
        catch (Throwable ignored) { }
    }

    // Player position from its bounding box (feet centre) — no fragile pos fields.
    public static double[] playerPos() {
        Object p = thePlayer();
        if (p == null) return null;
        double[] b = aabb(entityBox(p));
        if (b == null) return null;
        return new double[] { (b[0] + b[3]) / 2.0, b[1], (b[2] + b[5]) / 2.0 };
    }

    // rotationYaw = the first float declared on the Entity base class (the top of the
    // player's hierarchy): prev/pos/motion are all doubles, yaw is the first float.
    private static Field yawField;
    private static boolean yawSearched;
    public static float playerYaw() {
        Object p = thePlayer();
        if (p == null) return Float.NaN;
        try {
            if (!yawSearched) {
                yawSearched = true;
                Class<?> base = p.getClass();
                while (base.getSuperclass() != null && base.getSuperclass() != Object.class)
                    base = base.getSuperclass();
                for (Field f : base.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == float.class) { f.setAccessible(true); yawField = f; break; }
                }
            }
            return yawField != null ? yawField.getFloat(p) : Float.NaN;
        } catch (Throwable t) { return Float.NaN; }
    }

    // Active potion effects as {id, durationTicks, amplifier}. PotionEffect's first
    // three declared ints are potionID, duration, amplifier (in order).
    private static Field[] effectInts;
    public static List<int[]> potions() {
        List<int[]> out = new ArrayList<int[]>();
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || potionMapField == null) return out;
        try {
            Map<?, ?> m = (Map<?, ?>) potionMapField.get(p);
            if (m == null) return out;
            for (Object v : m.values()) {
                if (v == null) continue;
                if (effectInts == null) {
                    List<Field> is = new ArrayList<Field>();
                    for (Field f : v.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() == int.class) { f.setAccessible(true); is.add(f); }
                    }
                    if (is.size() < 3) return out;
                    effectInts = new Field[] { is.get(0), is.get(1), is.get(2) };
                }
                out.add(new int[] { effectInts[0].getInt(v), effectInts[1].getInt(v), effectInts[2].getInt(v) });
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // --- ArmorStatus: InventoryPlayer armour/held durability ---------------
    // InventoryPlayer = the player field whose type declares TWO ItemStack[] arrays
    // (mainInventory then armorInventory) — its first int is currentItem.
    // ItemStack's ints in order are stackSize, animationsToGo, itemDamage; its first
    // obfuscated object field is the Item. Item's base class (zw) declares exactly two
    // ints: maxStackSize then maxDamage.
    private static Field inventoryField, mainArrField, armorArrField, curItemField;
    private static boolean invSearched;
    private static Field[] stackInts;
    private static Field stackItemField, itemMaxField;

    private static boolean resolveInventory(Object p) {
        if (inventoryField != null) return true;
        if (invSearched || stackClassS == null) return false;
        invSearched = true;
        try {
            for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                    List<Field> arrs = new ArrayList<Field>();
                    Field curi = null;
                    for (Field ff : t.getDeclaredFields()) {
                        if (Modifier.isStatic(ff.getModifiers())) continue;
                        if (ff.getType().isArray() && ff.getType().getComponentType() == stackClassS) arrs.add(ff);
                        else if (ff.getType() == int.class && curi == null) curi = ff;
                    }
                    if (arrs.size() >= 2 && curi != null) {
                        f.setAccessible(true);
                        arrs.get(0).setAccessible(true); arrs.get(1).setAccessible(true);
                        curi.setAccessible(true);
                        inventoryField = f; mainArrField = arrs.get(0); armorArrField = arrs.get(1); curItemField = curi;
                        System.out.println("[IEA] inventory = " + f.getName() + " (" + t.getName()
                                + ") main=" + arrs.get(0).getName() + " armor=" + arrs.get(1).getName()
                                + " current=" + curi.getName());
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // rows of {stack, int[]{slotCode, remaining, max, count}}: slotCode 0=Helmet
    // 1=Chest 2=Legs 3=Boots 4=Held; remaining/max -1 = item has no durability.
    public static List<Object[]> armorItems() {
        List<Object[]> out = new ArrayList<Object[]>();
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || !resolveInventory(p)) return out;
        try {
            Object inv = inventoryField.get(p);
            if (inv == null) return out;
            Object armor = armorArrField.get(inv);
            if (armor != null) {
                int n = Array.getLength(armor); // [0]=boots .. [3]=helmet
                for (int slot = n - 1; slot >= 0; slot--) {
                    Object s = Array.get(armor, slot);
                    if (s != null) out.add(armorRow(s, (n - 1) - slot));
                }
            }
            // held item: read ItemRenderer.itemToRender directly (reliable), fall back
            // to mainInventory[currentItem] if the ItemRenderer isn't resolved yet
            Object held = heldStack();
            if (held == null) {
                Object main = mainArrField.get(inv);
                int ci = curItemField.getInt(inv);
                if (main != null && ci >= 0 && ci < Array.getLength(main)) held = Array.get(main, ci);
            }
            if (held != null) out.add(armorRow(held, 4));
        } catch (Throwable ignored) { }
        return out;
    }

    // the currently-held stack = ItemRenderer's only ItemStack field (itemToRender)
    private static Field heldStackField;
    public static Object heldStack() {
        ensureItemRenderer();
        if (itemRenderer == null || stackClassS == null) return null;
        try {
            if (heldStackField == null) {
                for (Field f : itemRendererClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == stackClassS) { f.setAccessible(true); heldStackField = f; break; }
                }
            }
            return heldStackField != null ? heldStackField.get(itemRenderer) : null;
        } catch (Throwable t) { return null; }
    }

    private static Object[] armorRow(Object stack, int slot) {
        int remain = -1, max = -1, count = 1;
        try {
            if (ensureStackFields(stack)) {
                count = stackInts[0].getInt(stack);
                int dmg = stackInts[2].getInt(stack);
                Object item = stackItemField.get(stack);
                if (item != null && ensureItemMax(item)) {
                    int m = itemMaxField.getInt(item);
                    if (m > 0) { max = m; remain = Math.max(0, m - dmg); }
                }
            }
        } catch (Throwable ignored) { }
        return new Object[] { stack, new int[] { slot, remain, max, count } };
    }

    private static boolean ensureStackFields(Object stack) {
        if (stackInts != null) return true;
        List<Field> is = new ArrayList<Field>();
        for (Field f : stack.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t == int.class) { f.setAccessible(true); is.add(f); }
            else if (stackItemField == null && !t.isPrimitive() && !t.isArray()
                    && t.getName().indexOf('.') < 0) {
                f.setAccessible(true); stackItemField = f;
            }
        }
        if (is.size() < 3 || stackItemField == null) return false;
        stackInts = new Field[] { is.get(0), is.get(1), is.get(2) };
        return true;
    }

    private static boolean ensureItemMax(Object item) {
        if (itemMaxField != null) return true;
        Class<?> base = item.getClass();
        while (base.getSuperclass() != null && base.getSuperclass() != Object.class)
            base = base.getSuperclass();
        int seen = 0;
        for (Field f : base.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == int.class && ++seen == 2) { f.setAccessible(true); itemMaxField = f; return true; }
        }
        return false;
    }

    // --- item icon rendering via vanilla RenderItem (mc.ab = bjh) -----------
    // RenderItem is the Minecraft field whose type declares TWO (ItemStack,int,int)->void
    // methods (renderItemIntoGUI / renderItemAndEffectIntoGUI). Draws a 16x16 icon at
    // GUI coords (0,0) — the caller sets up the matrices.
    private static Object renderItemInst;
    private static Method renderItemM, renderItemAlt;
    private static boolean riSearched;
    public static boolean renderItemIcon(Object stack) {
        if (!riSearched) { riSearched = true; discoverRenderItem(); }
        if (renderItemInst == null || renderItemM == null) return false;
        try {
            renderItemM.invoke(renderItemInst, stack, Integer.valueOf(0), Integer.valueOf(0));
            return true;
        } catch (Throwable t) {
            if (renderItemAlt != null) { renderItemM = renderItemAlt; renderItemAlt = null; }
            else renderItemM = null;
            return false;
        }
    }

    private static void discoverRenderItem() {
        if (minecraft == null || stackClassS == null) return;
        try {
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                List<Method> ms = new ArrayList<Method>();
                for (Method m : t.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getReturnType() == void.class && ps.length == 3
                            && ps[0] == stackClassS && ps[1] == int.class && ps[2] == int.class)
                        ms.add(m);
                }
                if (ms.size() >= 2) {
                    f.setAccessible(true);
                    Object v = f.get(minecraft);
                    if (v == null) continue;
                    // prefer the alphabetically first (declared first = renderItemIntoGUI)
                    Method a = ms.get(0), b = ms.get(1);
                    if (b.getName().compareTo(a.getName()) < 0) { Method tmp = a; a = b; b = tmp; }
                    a.setAccessible(true); b.setAccessible(true);
                    renderItemInst = v; renderItemM = a; renderItemAlt = b;
                    System.out.println("[IEA] RenderItem = " + t.getName()
                            + " draw=" + a.getName() + "/" + b.getName());
                    return;
                }
            }
            System.out.println("[IEA] RenderItem not found (armor icons unavailable)");
        } catch (Throwable ignored) { }
    }

    // --- stat bars: health / hunger / armor ---------------------------------
    // health = DataWatcher float at index 6 (vanilla 1.8.9). Reuses the DataWatcher
    // field discovered for the invisibility check.
    private static Method dwFloatGetter;
    public static float health() {
        Object p = thePlayer();
        if (p == null) return -1f;
        try {
            if (dataWatcherField == null) { isInvisible(p); if (dataWatcherField == null) return -1f; }
            Object dw = dataWatcherField.get(p);
            if (dw == null) return -1f;
            if (dwFloatGetter == null) {
                for (Method m : dw.getClass().getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getReturnType() == float.class && ps.length == 1 && ps[0] == int.class) {
                        m.setAccessible(true); dwFloatGetter = m; break;
                    }
                }
            }
            return dwFloatGetter != null
                    ? ((Float) dwFloatGetter.invoke(dw, Integer.valueOf(6))).floatValue() : -1f;
        } catch (Throwable t) { return -1f; }
    }

    // FoodStats = the player field whose type declares ONLY ints (>=2) and floats
    // (>=2) — foodLevel is its first int.
    private static Field foodStatsField, foodLevelField;
    private static boolean foodSearched;
    public static int foodLevel() {
        Object p = thePlayer();
        if (p == null) return -1;
        try {
            if (!foodSearched) {
                foodSearched = true;
                outer:
                for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass())
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        Class<?> t = f.getType();
                        if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                        int ints = 0, floats = 0, other = 0;
                        for (Field ff : t.getDeclaredFields()) {
                            if (Modifier.isStatic(ff.getModifiers())) continue;
                            if (ff.getType() == int.class) ints++;
                            else if (ff.getType() == float.class) floats++;
                            else other++;
                        }
                        if (ints >= 2 && floats >= 2 && other == 0) {
                            f.setAccessible(true); foodStatsField = f;
                            for (Field ff : t.getDeclaredFields())
                                if (!Modifier.isStatic(ff.getModifiers()) && ff.getType() == int.class) {
                                    ff.setAccessible(true); foodLevelField = ff; break;
                                }
                            System.out.println("[IEA] foodStats = " + f.getName()
                                    + " level=" + (foodLevelField != null ? foodLevelField.getName() : "?"));
                            break outer;
                        }
                    }
            }
            if (foodStatsField == null || foodLevelField == null) return -1;
            Object fs = foodStatsField.get(p);
            return fs != null ? foodLevelField.getInt(fs) : -1;
        } catch (Throwable t) { return -1; }
    }

    // total armor points: sum of ItemArmor.damageReduceAmount over worn pieces.
    // ItemArmor = the class in the item's hierarchy with an enum field (ArmorMaterial)
    // and >= 3 ints, declared in order armorType, damageReduceAmount, renderIndex.
    private static Field armorReduceField;
    public static int armorPoints() {
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || !resolveInventory(p)) return 0;
        int total = 0;
        try {
            Object inv = inventoryField.get(p);
            if (inv == null) return 0;
            Object armor = armorArrField.get(inv);
            if (armor == null) return 0;
            for (int i = 0; i < Array.getLength(armor); i++) {
                Object s = Array.get(armor, i);
                if (s == null || !ensureStackFields(s)) continue;
                Object item = stackItemField.get(s);
                if (item == null) continue;
                if (armorReduceField == null || !armorReduceField.getDeclaringClass().isInstance(item)) {
                    Field found = null;
                    for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        boolean hasEnum = false;
                        List<Field> is = new ArrayList<Field>();
                        for (Field ff : c.getDeclaredFields()) {
                            if (Modifier.isStatic(ff.getModifiers())) continue;
                            if (ff.getType().isEnum()) hasEnum = true;
                            else if (ff.getType() == int.class) is.add(ff);
                        }
                        if (hasEnum && is.size() >= 3) { is.get(1).setAccessible(true); found = is.get(1); break; }
                    }
                    if (found == null) continue;
                    armorReduceField = found;
                }
                if (armorReduceField.getDeclaringClass().isInstance(item))
                    total += armorReduceField.getInt(item);
            }
        } catch (Throwable ignored) { }
        return total;
    }

    // --- RenderHelper (vanilla GUI item lighting, cache-routed) ------------
    // RenderHelper = the only class with a static FloatBuffer + two static Vec3
    // (3-doubles-only) fields + EXACTLY three static ()->void methods, which in
    // declaration/name order are disableStandardItemLighting, enableStandardItem-
    // Lighting, enableGUIStandardItemLighting. Calling these goes through
    // GlStateManager, so the cache stays consistent (unlike our raw replica).
    private static Method rhDisable, rhEnableGui;
    private static boolean rhSearched;

    public static boolean guiItemLightingOn() {
        if (!rhSearched) { rhSearched = true; discoverRenderHelper(); }
        if (rhEnableGui == null) return false;
        try { rhEnableGui.invoke(null); return true; } catch (Throwable t) { return false; }
    }

    public static void guiItemLightingOff() {
        if (rhDisable == null) return;
        try { rhDisable.invoke(null); } catch (Throwable ignored) { }
    }

    private static void discoverRenderHelper() {
        Instrumentation in = Agent.inst;
        if (in == null) return;
        try {
            for (Class<?> c : in.getAllLoadedClasses()) {
                if (c == null || c.getName().indexOf('.') >= 0 || c.isInterface() || c.isArray()) continue;
                boolean fb = false; int vec3 = 0;
                try {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        Class<?> t = f.getType();
                        if (java.nio.FloatBuffer.class.isAssignableFrom(t)) fb = true;
                        else if (!t.isPrimitive() && !t.isArray() && t.getName().indexOf('.') < 0 && isVec3(t)) vec3++;
                    }
                } catch (Throwable skip) { continue; }
                if (!fb || vec3 < 2) continue;
                List<Method> vs = new ArrayList<Method>();
                for (Method m : c.getDeclaredMethods())
                    if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                            && m.getParameterTypes().length == 0) vs.add(m);
                if (vs.size() != 3) continue;
                // sort the three by name = declaration order
                for (int i = 0; i < vs.size(); i++)
                    for (int j = i + 1; j < vs.size(); j++)
                        if (vs.get(j).getName().compareTo(vs.get(i).getName()) < 0) {
                            Method t = vs.get(i); vs.set(i, vs.get(j)); vs.set(j, t);
                        }
                vs.get(0).setAccessible(true); vs.get(2).setAccessible(true);
                rhDisable = vs.get(0);   // disableStandardItemLighting
                rhEnableGui = vs.get(2); // enableGUIStandardItemLighting
                System.out.println("[IEA] RenderHelper = " + c.getName()
                        + " disable=" + rhDisable.getName() + " enableGUI=" + rhEnableGui.getName());
                return;
            }
            System.out.println("[IEA] RenderHelper not found (raw item lighting fallback)");
        } catch (Throwable ignored) { }
    }

    private static boolean isVec3(Class<?> c) {
        int d = 0, o = 0;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == double.class) d++; else o++;
        }
        return d == 3 && o == 0;
    }

    // --- GlStateManager cache sync -----------------------------------------
    // When we tweak blend / alpha-test with RAW GL, Minecraft's GlStateManager keeps its
    // own cached copy of those flags. If they diverge, vanilla skips a needed enable/
    // disable next frame (leaves turn see-through; the block-selection overlay loses its
    // transparency). GlStateManager is the top-level class with several static fields of
    // its OWN nested state types; AlphaState = {boolean,int,float}, BlendState =
    // {boolean, >=2 int, no float}. We grab those two objects + their boolean flag so we
    // can write the cache to match the raw state we set.
    // GlStateManager's enable flags are BooleanState objects { int capability; boolean
    // currentState; }. We hold the blend/alpha BooleanState + its currentState field so
    // we can write the cached flag to match the raw GL we set.
    private static Object blendBoolObj, alphaBoolObj;
    private static Field blendCurField, alphaCurField;
    private static boolean glStateSearched;

    private static void discoverGlState() {
        Instrumentation in = Agent.inst;
        if (in == null) return;
        try {
            for (Class<?> c : in.getAllLoadedClasses()) {
                if (c == null || c.getName().indexOf('.') >= 0 || c.isInterface() || c.isArray()) continue;
                // identify GlStateManager by its API shape: a static void (int,float)
                // [alphaFunc] plus many static no-arg void toggles.
                boolean hasAlphaFunc = false; int voidNoArg = 0;
                try {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length == 0) voidNoArg++;
                        else if (ps.length == 2 && ps[0] == int.class && ps[1] == float.class) hasAlphaFunc = true;
                    }
                } catch (Throwable skip) { continue; }
                if (!hasAlphaFunc || voidNoArg < 8) continue;

                // collect BooleanState objects reachable from its static fields (either a
                // direct static BooleanState, or nested one level inside a state object like
                // BlendState/AlphaState), then match by capability (GL_BLEND / GL_ALPHA_TEST).
                try {
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        Class<?> t = f.getType();
                        if (t.isPrimitive() || t.isArray()) continue;
                        f.setAccessible(true);
                        Object fv = f.get(null);
                        if (fv == null) continue;
                        if (isBooleanState(t)) { considerBoolState(fv); }
                        else {
                            for (Field g : t.getDeclaredFields()) {
                                if (Modifier.isStatic(g.getModifiers())) continue;
                                if (!isBooleanState(g.getType())) continue;
                                g.setAccessible(true);
                                Object bs = g.get(fv);
                                if (bs != null) considerBoolState(bs);
                            }
                        }
                    }
                } catch (Throwable skip) { }
                System.out.println("[IEA] GlStateManager = " + c.getName()
                        + " blendCache=" + (blendBoolObj != null) + " alphaCache=" + (alphaBoolObj != null));
                return;
            }
            System.out.println("[IEA] GlStateManager not found (blend/alpha cache sync off)");
        } catch (Throwable ignored) { }
    }

    // BooleanState = exactly { int capability; boolean currentState; }
    private static boolean isBooleanState(Class<?> t) {
        if (t.isPrimitive() || t.isArray()) return false;
        int ints = 0, bools = 0, other = 0;
        for (Field g : t.getDeclaredFields()) {
            if (Modifier.isStatic(g.getModifiers())) continue;
            Class<?> gt = g.getType();
            if (gt == int.class) ints++;
            else if (gt == boolean.class) bools++;
            else other++;
        }
        return ints == 1 && bools == 1 && other == 0;
    }

    private static void considerBoolState(Object bs) {
        try {
            Field capF = null, curF = null;
            for (Field g : bs.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(g.getModifiers())) continue;
                if (g.getType() == int.class) capF = g;
                else if (g.getType() == boolean.class) curF = g;
            }
            if (capF == null || curF == null) return;
            capF.setAccessible(true); curF.setAccessible(true);
            int cap = capF.getInt(bs);
            if (cap == GL11.GL_BLEND && blendBoolObj == null) { blendBoolObj = bs; blendCurField = curF; }
            else if (cap == GL11.GL_ALPHA_TEST && alphaBoolObj == null) { alphaBoolObj = bs; alphaCurField = curF; }
        } catch (Throwable ignored) { }
    }

    /** Set blend / alpha-test with raw GL AND write GlStateManager's cached flag to match,
     *  so vanilla's next-frame enable/disable calls aren't wrongly skipped. */
    public static void syncBlendAlpha(boolean blendOn, boolean alphaOn) {
        if (blendOn) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
        if (alphaOn) GL11.glEnable(GL11.GL_ALPHA_TEST); else GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        if (!glStateSearched) { glStateSearched = true; discoverGlState(); }
        try {
            if (blendCurField != null && blendBoolObj != null) blendCurField.setBoolean(blendBoolObj, blendOn);
            if (alphaCurField != null && alphaBoolObj != null) alphaCurField.setBoolean(alphaBoolObj, alphaOn);
        } catch (Throwable ignored) { }
    }

    // --- IEA hotbar ---------------------------------------------------------
    // ScaledResolution: getScaledWidth/getScaledHeight are its first two no-arg
    // ()->int methods in name order ("a", "b"; "e" is the scale factor).
    private static Method srW, srH;
    public static int[] scaledSize(Object sr) {
        try {
            if (srW == null) {
                List<Method> ms = new ArrayList<Method>();
                for (Method m : sr.getClass().getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getReturnType() == int.class && m.getParameterTypes().length == 0) ms.add(m);
                }
                if (ms.size() < 2) return null;
                Method a = ms.get(0);
                for (Method m : ms) if (m.getName().compareTo(a.getName()) < 0) a = m;
                Method b = null;
                for (Method m : ms)
                    if (m != a && (b == null || m.getName().compareTo(b.getName()) < 0)) b = m;
                a.setAccessible(true); b.setAccessible(true);
                srW = a; srH = b;
            }
            return new int[] { ((Integer) srW.invoke(sr)).intValue(), ((Integer) srH.invoke(sr)).intValue() };
        } catch (Throwable t) { return null; }
    }

    public static int hotbarSelected() {
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || !resolveInventory(p)) return 0;
        try {
            Object inv = inventoryField.get(p);
            int s = (inv != null) ? curItemField.getInt(inv) : 0;
            return (s >= 0 && s < 9) ? s : 0;
        } catch (Throwable t) { return 0; }
    }

    // GameSettings.keyBindsHotbar (a KeyBinding[9]) + KeyBinding.keyCode (the single
    // non-final int), found structurally. Lets us replicate the vanilla "this key selects
    // hotbar slot N" mapping for mouse buttons in states where vanilla mis-handles them.
    private static Object keyBindsHotbarArr;
    private static Field kbKeyCodeField;
    private static boolean hotbarKbSearched;
    private static void resolveHotbarKeybinds() {
        if (hotbarKbSearched) return;
        if (gameSettings == null || keyBindSprint == null || gameSettingsClass == null) return;
        hotbarKbSearched = true;
        try {
            Class<?> kbClass = keyBindSprint.getClass();
            for (Field f : kbClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == int.class && !Modifier.isFinal(f.getModifiers())) {
                    f.setAccessible(true); kbKeyCodeField = f; break; // keyCode (mutable); keyCodeDefault is final
                }
            }
            for (Field f : gameSettingsClass.getDeclaredFields()) {
                if (!f.getType().isArray() || f.getType().getComponentType() != kbClass) continue;
                f.setAccessible(true);
                Object arr = f.get(gameSettings);
                if (arr != null && Array.getLength(arr) == 9) { keyBindsHotbarArr = arr; break; }
            }
            System.out.println("[IEA] hotbar keybinds=" + (keyBindsHotbarArr != null)
                    + " keyCodeField=" + (kbKeyCodeField != null ? kbKeyCodeField.getName() : "?"));
        } catch (Throwable ignored) { }
    }

    /** Hotbar slot index bound to keyCode, or -1. Reads the keyBindsHotbar array directly
     *  (vanilla's HASH-based dispatch can mis-map mouse buttons to the wrong slot — the bug
     *  this works around). */
    public static int slotForKey(int keyCode) {
        resolveHotbarKeybinds();
        if (keyBindsHotbarArr == null || kbKeyCodeField == null) return -1;
        try {
            for (int s = 0; s < 9; s++) {
                Object kb = Array.get(keyBindsHotbarArr, s);
                if (kb != null && kbKeyCodeField.getInt(kb) == keyCode) return s;
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    // The standard FontRenderer instance (Minecraft.fontRendererObj), found up-front so the
    // vanilla-font GUI path works from the very first frame instead of waiting for some
    // vanilla drawString call to cache it (the IEA main menu draws no vanilla text, so it
    // never got cached there -> buttons wrongly fell back to the IEA font).
    private static Object fontRendererObj;
    private static boolean frObjSearched;
    public static Object fontRenderer() {
        if (fontRendererObj != null) return fontRendererObj;
        if (frObjSearched || minecraft == null) return fontRendererObj;
        try {
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                boolean hasDraw = false;
                for (Method m : t.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == int.class && p.length == 5 && p[0] == String.class
                            && p[1] == float.class && p[2] == float.class && p[3] == int.class
                            && p[4] == boolean.class) { hasDraw = true; break; }
                }
                if (!hasDraw) continue;
                f.setAccessible(true);
                Object v = f.get(minecraft);
                if (v != null) { fontRendererObj = v; frObjSearched = true; return v; } // first = standard font
            }
            frObjSearched = true;
        } catch (Throwable ignored) { frObjSearched = true; }
        return fontRendererObj;
    }

    /** Force the held hotbar slot (no-op if already there). EntityPlayerSP syncs it to the
     *  server next tick, so it works in multiplayer. */
    public static void setHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || !resolveInventory(p)) return;
        try {
            Object inv = inventoryField.get(p);
            if (inv != null && curItemField.getInt(inv) != slot) curItemField.setInt(inv, slot);
        } catch (Throwable ignored) { }
    }

    // Forward a synthetic key press to the currently-open vanilla screen by calling its
    // keyTyped(char, int). The inventory wires the hover-to-hotbar swap to KEYBOARD keys
    // only (checkHotbarKeys, run from keyTyped); a mouse button bound to a hotbar slot
    // therefore does nothing there. We re-issue the button as its key code (MC convention:
    // mouse button index - 100) so vanilla performs the exact swap + server packet.
    private static Method screenKeyTypedM;
    private static Class<?> screenKeyTypedFor;
    public static boolean sendKeyToScreen(int keyCode) {
        if (minecraft == null || currentScreenField == null) return false;
        try {
            Object screen = currentScreenField.get(minecraft);
            if (screen == null) return false;
            if (screenKeyTypedFor != screen.getClass()) {
                screenKeyTypedFor = screen.getClass();
                screenKeyTypedM = findKeyTyped(screen.getClass());
            }
            if (screenKeyTypedM == null) return false;
            screenKeyTypedM.invoke(screen, Character.valueOf('\0'), Integer.valueOf(keyCode));
            return true;
        } catch (Throwable t) { return false; }
    }

    // keyTyped is the only instance (char, int) -> void method in the screen hierarchy
    private static Method findKeyTyped(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 2
                        && p[0] == char.class && p[1] == int.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    public static Object[] hotbarStacks() {
        Object p = thePlayer();
        if (p == null || !resolvePlayerFields(p) || !resolveInventory(p)) return null;
        try {
            Object inv = inventoryField.get(p);
            if (inv == null) return null;
            Object main = mainArrField.get(inv);
            if (main == null) return null;
            int n = Math.min(9, Array.getLength(main));
            Object[] out = new Object[9];
            for (int i = 0; i < n; i++) out[i] = Array.get(main, i);
            return out;
        } catch (Throwable t) { return null; }
    }

    // item + overlays (count/durability) at GUI coords — used inside the vanilla
    // overlay context, so the cache-routed calls stay consistent.
    private static Method overlayM;
    private static Object fontRendererInst;
    private static boolean overlaySearched;
    public static void renderHotbarItem(Object stack, int x, int y) {
        if (!riSearched) { riSearched = true; discoverRenderItem(); }
        if (renderItemInst == null || renderItemM == null) return;
        try {
            renderItemM.invoke(renderItemInst, stack, Integer.valueOf(x), Integer.valueOf(y));
            if (!overlaySearched) { overlaySearched = true; discoverOverlay(); }
            if (overlayM != null && fontRendererInst != null)
                overlayM.invoke(renderItemInst, fontRendererInst, stack, Integer.valueOf(x), Integer.valueOf(y));
        } catch (Throwable ignored) { }
    }

    // renderItemOverlayIntoGUI(FontRenderer, ItemStack, int, int) — the 4-arg void on
    // RenderItem whose first param is an obf class (the FontRenderer); its instance is
    // the first Minecraft field of that type.
    private static void discoverOverlay() {
        try {
            Class<?> frClass = null;
            for (Method m : renderItemInst.getClass().getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getReturnType() == void.class && ps.length == 4
                        && ps[1] == stackClassS && ps[2] == int.class && ps[3] == int.class
                        && !ps[0].isPrimitive() && ps[0].getName().indexOf('.') < 0
                        && ps[0] != stackClassS) {
                    m.setAccessible(true); overlayM = m; frClass = ps[0];
                    break;
                }
            }
            if (frClass == null || minecraft == null) return;
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == frClass) { f.setAccessible(true); fontRendererInst = f.get(minecraft); break; }
            }
            System.out.println("[IEA] hotbar overlays = " + (overlayM != null ? overlayM.getName() : "?")
                    + " font=" + (fontRendererInst != null));
        } catch (Throwable ignored) { }
    }

    // --- inventory.png binding for potion-effect icons ---------------------
    // TextureManager = the RenderItem field whose type has a void method taking a
    // single ResourceLocation (a default-package class with exactly two Strings).
    // Among those methods the alphabetically first is bindTexture ("a"; "c" would be
    // deleteTexture). ItemModelMesher (the other candidate field) has no such method.
    private static Object texManager;
    private static Method bindTexM;
    private static Object inventoryTexLoc;
    private static boolean texSearched;
    private static int invTexId = 0;
    private static boolean invTexRequested;

    // Raw GL id of inventory.png; 0 until captured. HUD code binds this RAW (never
    // through the cache) so the in-pass state stays GlStateManager-safe.
    public static int inventoryTexId() { return invTexId; }
    public static void requestInventoryTexture() { invTexRequested = true; }
    public static boolean inventoryTexturePending() { return invTexRequested && invTexId <= 0; }

    // One-time: bind through the vanilla TextureManager (loads the texture, keeps the
    // GlStateManager cache CONSISTENT because nothing restores raw state afterwards),
    // then read back the GL id for raw binds. Must be called from the deferred icon
    // stage (outside Surface), never inside a push/pop-attrib scope.
    public static void captureInventoryTexture() {
        invTexRequested = false;
        if (invTexId > 0) return;
        if (!texSearched) { texSearched = true; discoverTexManager(); }
        if (texManager == null || bindTexM == null || inventoryTexLoc == null) return;
        try {
            bindTexM.invoke(texManager, inventoryTexLoc);
            invTexId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            System.out.println("[IEA] inventory.png texId=" + invTexId);
        } catch (Throwable ignored) { }
    }

    private static void discoverTexManager() {
        try {
            if (!riSearched) { riSearched = true; discoverRenderItem(); }
            if (renderItemInst == null) return;
            for (Field f : renderItemInst.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                Method bind = null; Class<?> rl = null;
                for (Method m : t.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getReturnType() == void.class && ps.length == 1 && isResourceLocation(ps[0])) {
                        if (bind == null || m.getName().compareTo(bind.getName()) < 0) { bind = m; rl = ps[0]; }
                    }
                }
                if (bind == null) continue;
                f.setAccessible(true);
                Object v = f.get(renderItemInst);
                if (v == null) continue;
                bind.setAccessible(true);
                texManager = v; bindTexM = bind;
                inventoryTexLoc = rl.getConstructor(String.class)
                        .newInstance("textures/gui/container/inventory.png");
                System.out.println("[IEA] TextureManager = " + t.getName() + " bind=" + bind.getName());
                return;
            }
            System.out.println("[IEA] TextureManager not found (potion icons unavailable)");
        } catch (Throwable t) {
            System.out.println("[IEA] TextureManager discovery error: " + t);
        }
    }

    private static boolean isResourceLocation(Class<?> c) {
        if (c.isPrimitive() || c.isArray() || c.getName().indexOf('.') >= 0) return false;
        int s = 0, o = 0;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == String.class) s++; else o++;
        }
        return s == 2 && o == 0;
    }

    // --- Ping: NetHandlerPlayClient.playerInfoMap -> own NetworkPlayerInfo ----
    // NetHandler = the player field whose type declares BOTH a Map (playerInfoMap)
    // and a GameProfile (this jar: bew.a = bcy). The map is keyed by UUID; the info
    // object's first declared int is responseTime (= ping in ms).
    private static Field netHandlerField, infoMapField, myProfileField, infoPingField, infoProfileField;
    private static Method profGetId;
    private static boolean pingSearched;
    public static int pingMs() {
        Object p = thePlayer();
        if (p == null) return -1;
        try {
            if (!pingSearched) { pingSearched = true; discoverPing(p); }
            if (netHandlerField == null || myProfileField == null) return -1;
            Object nh = netHandlerField.get(p);
            Object prof = myProfileField.get(p);
            if (nh == null || prof == null) return -1;
            Map<?, ?> map = (Map<?, ?>) infoMapField.get(nh);
            if (map == null) return -1;
            if (profGetId == null) profGetId = prof.getClass().getMethod("getId");
            Object myId = profGetId.invoke(prof);
            Object info = map.get(myId);
            if (info == null) { // fallback: match by GameProfile id
                for (Object v : map.values()) {
                    if (v == null) continue;
                    if (infoProfileField == null) {
                        for (Field f : v.getClass().getDeclaredFields())
                            if (f.getType().getName().equals("com.mojang.authlib.GameProfile")) {
                                f.setAccessible(true); infoProfileField = f; break;
                            }
                        if (infoProfileField == null) break;
                    }
                    Object vp = infoProfileField.get(v);
                    if (vp != null && myId.equals(profGetId.invoke(vp))) { info = v; break; }
                }
            }
            if (info == null) return -1;
            if (infoPingField == null) {
                for (Field f : info.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == int.class) { f.setAccessible(true); infoPingField = f; break; }
                }
            }
            return infoPingField != null ? infoPingField.getInt(info) : -1;
        } catch (Throwable t) { return -1; }
    }

    private static void discoverPing(Object p) {
        try {
            outer:
            for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                    Field map = null; boolean prof = false;
                    for (Field ff : t.getDeclaredFields()) {
                        if (Map.class.isAssignableFrom(ff.getType()) && map == null) map = ff;
                        if (ff.getType().getName().equals("com.mojang.authlib.GameProfile")) prof = true;
                    }
                    if (map != null && prof) {
                        f.setAccessible(true); map.setAccessible(true);
                        netHandlerField = f; infoMapField = map;
                        break outer;
                    }
                }
            }
            outer2:
            for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields())
                    if (f.getType().getName().equals("com.mojang.authlib.GameProfile")) {
                        f.setAccessible(true); myProfileField = f; break outer2;
                    }
            System.out.println("[IEA] ping: netHandler=" + (netHandlerField != null ? netHandlerField.getName() : "?")
                    + " map=" + (infoMapField != null ? infoMapField.getName() : "?")
                    + " profile=" + (myProfileField != null ? myProfileField.getName() : "?"));
        } catch (Throwable ignored) { }
    }

    // --- ServerAddress: NetHandlerPlayClient.netManager.getRemoteAddress() ---
    // Reuses the NetHandler discovered for Ping. The NetworkManager is the NetHandler
    // field whose type declares a no-arg method returning a java.net.SocketAddress
    // (getRemoteAddress) — SocketAddress is a JDK type, so this pins it unambiguously
    // with no Notch names. Returns null in singleplayer (LOCAL channel) / when offline.
    private static Field netManagerField;
    private static Method getRemoteAddrM;
    private static boolean addrSearched;
    public static String serverAddress() {
        Object p = thePlayer();
        if (p == null) return null;
        try {
            if (netHandlerField == null && !pingSearched) { pingSearched = true; discoverPing(p); }
            if (netHandlerField == null) return null;
            Object nh = netHandlerField.get(p);
            if (nh == null) return null;
            if (!addrSearched) {
                addrSearched = true;
                for (Field f : nh.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                    Method m = noArgSocketAddr(t);
                    if (m != null) {
                        f.setAccessible(true);
                        netManagerField = f; getRemoteAddrM = m;
                        System.out.println("[IEA] networkManager = " + f.getName()
                                + " remoteAddr=" + m.getName());
                        break;
                    }
                }
            }
            if (netManagerField == null || getRemoteAddrM == null) return null;
            Object nm = netManagerField.get(nh);
            if (nm == null) return null;
            Object addr = getRemoteAddrM.invoke(nm);
            if (addr == null) return null;
            String s = addr.toString();
            if (s.startsWith("local") || s.startsWith("embedded")) return null; // singleplayer channel
            if (s.startsWith("/")) s = s.substring(1);
            return s;
        } catch (Throwable t) { return null; }
    }

    private static Method noArgSocketAddr(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (java.net.SocketAddress.class.isAssignableFrom(m.getReturnType())) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // Minecraft.currentScreen != null (a vanilla GUI like inventory/chat is open).
    // The field is found structurally: GuiScreen (axu) is the ONLY field type on the
    // Minecraft class that declares a java.net.URI field (clickedLinkURI).
    private static Field currentScreenField;
    private static boolean screenSearched;
    public static boolean isVanillaScreenOpen() {
        if (minecraft == null) return false;
        try {
            if (!screenSearched) {
                screenSearched = true;
                for (Field f : minecraft.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                    if (hasUriField(t)) {
                        f.setAccessible(true); currentScreenField = f;
                        System.out.println("[IEA] currentScreen = " + f.getName() + " (" + t.getName() + ")");
                        break;
                    }
                }
            }
            return currentScreenField != null && currentScreenField.get(minecraft) != null;
        } catch (Throwable t) { return false; }
    }

    private static boolean hasUriField(Class<?> c) {
        for (Field f : c.getDeclaredFields()) if (f.getType() == java.net.URI.class) return true;
        return false;
    }

    // --- GuiButton (obf avs) field access for the restyled-button draw ----
    // Resolved by structure: the class in the button's hierarchy declaring >=4 ints +
    // a String + >=3 booleans is GuiButton. Field order (MCP) is width, height,
    // xPosition, yPosition (ints), displayString (String), id (int), enabled, visible,
    // hovered (booleans).
    private static boolean btnResolved;
    private static Field bX, bY, bW, bH, bEnabled, bVisible, bHovered, bText;
    private static Method mouseDraggedM; // GuiButton.mouseDragged(Minecraft,int,int) (protected)
    private static void resolveButton(Object b) {
        if (btnResolved) return;
        btnResolved = true;
        try {
            Class<?> gb = null;
            for (Class<?> c = b.getClass(); c != null; c = c.getSuperclass()) {
                int ni = 0, ns = 0, nb = 0;
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t == int.class) ni++;
                    else if (t == String.class) ns++;
                    else if (t == boolean.class) nb++;
                }
                if (ni >= 4 && ns >= 1 && nb >= 3) { gb = c; break; }
            }
            if (gb == null) { System.out.println("[IEA] GuiButton fields not found"); return; }
            // mouseDragged = the PROTECTED (Minecraft,int,int)->void on GuiButton
            // (drawButton has the same descriptor but is public). Invoking it virtually
            // runs a slider subclass's override (knob + drag).
            for (Method m : gb.getDeclaredMethods()) {
                if (m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[1] == int.class && p[2] == int.class
                        && !p[0].isPrimitive() && p[0].getName().indexOf('.') < 0
                        && Modifier.isProtected(m.getModifiers())) {
                    m.setAccessible(true); mouseDraggedM = m;
                    System.out.println("[IEA] GuiButton.mouseDragged = " + gb.getName() + "." + m.getName());
                    break;
                }
            }
            List<Field> ints = new ArrayList<Field>(), bools = new ArrayList<Field>();
            for (Field f : gb.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                f.setAccessible(true);
                if (t == int.class) ints.add(f);
                else if (t == String.class && bText == null) bText = f;
                else if (t == boolean.class) bools.add(f);
            }
            if (ints.size() >= 4) { bW = ints.get(0); bH = ints.get(1); bX = ints.get(2); bY = ints.get(3); }
            if (bools.size() >= 3) { bEnabled = bools.get(0); bVisible = bools.get(1); bHovered = bools.get(2); }
            System.out.println("[IEA] GuiButton = " + gb.getName());
        } catch (Throwable t) {
            System.out.println("[IEA] GuiButton resolve error: " + t);
        }
    }

    public static boolean btnReady(Object b) { resolveButton(b); return bX != null && bText != null; }
    public static int btnX(Object b) { try { return bX.getInt(b); } catch (Throwable t) { return 0; } }
    public static int btnY(Object b) { try { return bY.getInt(b); } catch (Throwable t) { return 0; } }
    public static int btnW(Object b) { try { return bW.getInt(b); } catch (Throwable t) { return 0; } }
    public static int btnH(Object b) { try { return bH.getInt(b); } catch (Throwable t) { return 0; } }
    public static boolean btnVisible(Object b) { try { return bVisible == null || bVisible.getBoolean(b); } catch (Throwable t) { return true; } }
    public static boolean btnEnabled(Object b) { try { return bEnabled == null || bEnabled.getBoolean(b); } catch (Throwable t) { return true; } }
    public static String btnText(Object b) { try { Object s = bText.get(b); return s == null ? "" : s.toString(); } catch (Throwable t) { return ""; } }
    public static void btnSetHovered(Object b, boolean h) { try { if (bHovered != null) bHovered.setBoolean(b, h); } catch (Throwable ignored) { } }

    // Run the button's mouseDragged (what vanilla drawButton does): sliders draw their
    // knob and update while dragging here; plain buttons no-op. Virtual dispatch via
    // reflection runs the actual subclass override.
    public static void callMouseDragged(Object btn, Object mc, int mx, int my) {
        if (mouseDraggedM == null) { resolveButton(btn); if (mouseDraggedM == null) return; }
        try { mouseDraggedM.invoke(btn, mc, Integer.valueOf(mx), Integer.valueOf(my)); } catch (Throwable ignored) { }
    }

    // --- GuiScreen width/height (scaled GUI units) for the main-menu background ---
    // GuiScreen is the class in the screen's hierarchy with a Minecraft field + a List
    // field; its first two declared ints are width then height.
    // GuiScreen = the class in the hierarchy declaring >= 2 List fields (buttonList,
    // labelList) and >= 2 ints (width, height). First two ints = width, height; first
    // List = buttonList. No dependence on the Minecraft singleton being discovered yet.
    private static boolean guiResolved;
    private static Field gW, gH, gButtons;
    private static void resolveGui(Object screen) {
        if (guiResolved) return;
        guiResolved = true;
        try {
            Class<?> best = null;
            // keep walking up and remember the HIGHEST matching class (= GuiScreen itself,
            // not a subclass that happens to also declare lists/ints)
            for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
                List<Field> ints = new ArrayList<Field>(), lists = new ArrayList<Field>();
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (java.util.List.class.isAssignableFrom(t)) lists.add(f);
                    else if (t == int.class) ints.add(f);
                }
                if (lists.size() >= 2 && ints.size() >= 2) {
                    gW = ints.get(0); gH = ints.get(1); gButtons = lists.get(0);
                    best = c;
                }
            }
            if (best != null) {
                gW.setAccessible(true); gH.setAccessible(true); gButtons.setAccessible(true);
                System.out.println("[IEA] GuiScreen = " + best.getName() + " w=" + gW.getName()
                        + " h=" + gH.getName() + " buttons=" + gButtons.getName());
            }
        } catch (Throwable ignored) { }
    }
    public static int guiW(Object s) { resolveGui(s); try { return gW.getInt(s); } catch (Throwable t) { return 0; } }
    public static int guiH(Object s) { resolveGui(s); try { return gH.getInt(s); } catch (Throwable t) { return 0; } }
    public static java.util.List<?> screenButtons(Object s) {
        resolveGui(s);
        try { Object v = gButtons.get(s); return (v instanceof java.util.List) ? (java.util.List<?>) v : null; }
        catch (Throwable t) { return null; }
    }

    // A slider is any GuiButton subclass carrying a 0..1 float (e.g. sliderValue). The
    // GuiButton base (avs) has no float field, so the first float in the hierarchy is it.
    public static float btnSliderValue(Object b) {
        try {
            for (Class<?> c = b.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == float.class) {
                        f.setAccessible(true);
                        float v = f.getFloat(b);
                        return v < 0f ? 0f : (v > 1f ? 1f : v);
                    }
                }
            }
        } catch (Throwable ignored) { }
        return -1f;
    }

    // objectMouseOver (MovingObjectPosition) typeOfHit == BLOCK. We find it on the
    // Minecraft instance as the field whose obf type has >= 2 enum fields, one of which
    // has exactly 3 constants (the MovingObjectType: MISS / BLOCK / ENTITY). The hit
    // type enum's ordinal is 0=MISS, 1=BLOCK, 2=ENTITY.
    private static Field mouseOverField, hitTypeField;
    private static boolean mouseOverSearched;
    private static void discoverMouseOver() {
        mouseOverSearched = true;
        if (minecraft == null) { mouseOverSearched = false; return; }
        try {
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray() || ft.getName().indexOf('.') >= 0) continue;
                Field three = null; int enums = 0;
                for (Field ff : ft.getDeclaredFields()) {
                    Class<?> t = ff.getType();
                    if (t.isEnum()) {
                        enums++;
                        Object[] cs = t.getEnumConstants();
                        if (cs != null && cs.length == 3) three = ff;
                    }
                }
                if (three != null && enums >= 2) {
                    f.setAccessible(true); three.setAccessible(true);
                    mouseOverField = f; hitTypeField = three;
                    System.out.println("[IEA] objectMouseOver = " + ft.getName() + " type=" + three.getName());
                    return;
                }
            }
            System.out.println("[IEA] objectMouseOver not found (swing-on-block inactive)");
        } catch (Throwable t) {
            System.out.println("[IEA] objectMouseOver discovery error: " + t);
        }
    }

    public static boolean isLookingAtBlock() {
        if (minecraft == null) return false;
        if (!mouseOverSearched) discoverMouseOver();
        if (mouseOverField == null || hitTypeField == null) return false;
        try {
            Object mo = mouseOverField.get(minecraft);
            if (mo == null) return false;
            Object type = hitTypeField.get(mo);
            return (type instanceof Enum) && ((Enum<?>) type).ordinal() == 1; // BLOCK
        } catch (Throwable t) { return false; }
    }

    // --- render-feature discovery -----------------------------------------
    // Walks the object graph from the Minecraft singleton (up to 3 hops) and
    // writes every reachable obfuscated class with its fields + method
    // signatures to iea-render-dump.txt. Run this WHILE IN A WORLD (press F6) so
    // EntityRenderer / RenderGlobal / WorldClient / EntityPlayerSP / ItemRenderer
    // / objectMouseOver are all populated, then pin the obfuscated names to
    // ASM-hook them for block overlay / hitbox / TNT timer / 1.7 anim.
    public static void dumpRenderInfo() {
        if (minecraft == null) { System.out.println("[IEA][render] not ready yet"); return; }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("IEA render discovery dump\n");
            sb.append("Minecraft = ").append(minecraft.getClass().getName()).append("\n");

            LinkedHashMap<String, Class<?>> classes = new LinkedHashMap<String, Class<?>>();
            List<Object> level = new ArrayList<Object>();
            level.add(minecraft);
            classes.put(minecraft.getClass().getName(), minecraft.getClass());

            // breadth-first, max 3 hops, capped so we never flood
            for (int depth = 0; depth < 3 && classes.size() < 200; depth++) {
                List<Object> next = new ArrayList<Object>();
                for (Object obj : level) {
                    if (obj == null) continue;
                    for (Field f : obj.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        try {
                            f.setAccessible(true);
                            // also descends into Map / Collection / array values, so
                            // objects held only in containers (e.g. RenderManager's
                            // entityRenderMap -> RendererLivingEntity) are reached too
                            collect(f.get(obj), classes, next);
                        } catch (Throwable ignored) { }
                    }
                }
                level = next;
            }

            // expand by STATIC type graph: superclasses + field types + method
            // return/param types. This reaches classes we can't get an instance of
            // (Entity base, EntityPlayer, BlockPos, AxisAlignedBB, draw helpers...).
            ArrayDeque<Class<?>> q = new ArrayDeque<Class<?>>(classes.values());
            while (!q.isEmpty() && classes.size() < 600) {
                Class<?> c = q.poll();
                addType(classes, q, c.getSuperclass());
                try {
                    for (Field f : c.getDeclaredFields()) addType(classes, q, f.getType());
                    for (Method m : c.getDeclaredMethods()) {
                        addType(classes, q, m.getReturnType());
                        for (Class<?> p : m.getParameterTypes()) addType(classes, q, p);
                    }
                } catch (Throwable ignored) { }
            }

            // GUARANTEE every reachable class's full superclass chain is present (uncapped),
            // so base classes like RenderLivingBase / EntityLivingBase are always dumped
            // even when the cap above truncated the breadth-first graph.
            ArrayDeque<Class<?>> sc = new ArrayDeque<Class<?>>(classes.values());
            while (!sc.isEmpty()) {
                Class<?> s = sc.poll().getSuperclass();
                if (s == null || s.getName().indexOf('.') >= 0) continue;
                if (!classes.containsKey(s.getName())) { classes.put(s.getName(), s); sc.add(s); }
            }

            sb.append("\n== Minecraft instance fields (name : type ~runtime) ==\n");
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String line = "  " + f.getName() + " : " + simple(f.getType().getName());
                try {
                    f.setAccessible(true);
                    Object v = f.get(minecraft);
                    if (v != null && v.getClass().getName().indexOf('.') < 0)
                        line += "  ~" + v.getClass().getName();
                } catch (Throwable ignored) { }
                sb.append(line).append("\n");
            }

            sb.append("\n== reachable classes (fields + methods) ==\n");
            for (Class<?> c : classes.values()) {
                Class<?> sup = c.getSuperclass();
                sb.append("\n[").append(c.getName()).append("]  extends ")
                        .append(sup != null ? sup.getName() : "?").append("\n");
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    sb.append("  . ").append(f.getName()).append(" : ").append(simple(f.getType().getName())).append("\n");
                }
                for (Method m : c.getDeclaredMethods()) {
                    sb.append("  () ").append(m.getName()).append(sig(m)).append("\n");
                }
            }

            File out = new File(System.getProperty("user.dir"), "iea-render-dump.txt");
            FileWriter fw = new FileWriter(out);
            fw.write(sb.toString());
            fw.close();
            System.out.println("[IEA][render] wrote discovery dump to " + out.getAbsolutePath()
                    + " (" + classes.size() + " classes)");
        } catch (Throwable t) {
            System.out.println("[IEA][render] dump error: " + t);
        }
    }

    // add a reachable value to the dump's object graph: an obf object is recorded and
    // queued for the next hop; Map / Collection / object-array values are walked (capped)
    // so objects living only inside containers are still reached.
    private static void collect(Object v, LinkedHashMap<String, Class<?>> classes, List<Object> next) {
        if (v == null) return;
        Class<?> rc = v.getClass();
        if (rc.isArray()) {
            if (rc.getComponentType().isPrimitive()) return;
            int n = Math.min(Array.getLength(v), 64);
            for (int i = 0; i < n; i++) collect(Array.get(v, i), classes, next);
            return;
        }
        if (v instanceof Map) {
            int c = 0;
            for (Object e : ((Map<?, ?>) v).values()) { if (c++ > 128) break; collect(e, classes, next); }
            return;
        }
        if (v instanceof java.util.Collection) {
            int c = 0;
            for (Object e : (java.util.Collection<?>) v) { if (c++ > 128) break; collect(e, classes, next); }
            return;
        }
        if (rc.getName().indexOf('.') >= 0) return; // only obfuscated (default-package) classes
        if (!classes.containsKey(rc.getName())) {
            classes.put(rc.getName(), rc);
            next.add(v);
        }
    }

    // add an obfuscated (default-package) class to the dump set, unwrapping arrays
    private static void addType(LinkedHashMap<String, Class<?>> set, ArrayDeque<Class<?>> q, Class<?> t) {
        if (t == null) return;
        while (t.isArray()) t = t.getComponentType();
        if (t.isPrimitive()) return;
        String n = t.getName();
        if (n.indexOf('.') >= 0) return;          // skip java.* / com.* etc.
        if (set.containsKey(n)) return;
        set.put(n, t);
        q.add(t);
    }

    private static String sig(Method m) {
        StringBuilder s = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) s.append(", ");
            s.append(simple(ps[i].getName()));
        }
        return s.append(") -> ").append(simple(m.getReturnType().getName())).toString();
    }

    // shorten array / java.lang names for readability
    private static String simple(String n) {
        if (n.startsWith("java.lang.")) return n.substring(10);
        return n;
    }

    // --- helpers to read/write a float field on GameSettings by name ---
    public static void setSettingFloat(String fieldName, float value) {
        if (gameSettings == null) return;
        try {
            Field f = gameSettingsClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.setFloat(gameSettings, value);
        } catch (Throwable ignored) { }
    }

    public static float getSettingFloat(String fieldName, float def) {
        if (gameSettings == null) return def;
        try {
            Field f = gameSettingsClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getFloat(gameSettings);
        } catch (Throwable ignored) { return def; }
    }

    // --- HitParticles: spawn REAL vanilla particles via EffectRenderer ------
    // EffectRenderer = the Minecraft field whose type declares spawnEffectParticle:
    //   (int id, double x,y,z, double mx,my,mz, int... args) -> EntityFX (an obf object).
    // That descriptor (I,D,D,D,D,D,D,[I) -> non-primitive is unique among Minecraft's
    // fields, so it pins the class with no Notch name mappings. The call BOTH creates
    // and registers the particle, so the game ticks / renders / fades it natively.
    // particleId follows EnumParticleTypes.getParticleID(): 9 = crit, 10 = magicCrit
    // (the cyan "sharpness"/enchant hit sparkle).
    private static Object effectRenderer;
    private static Method spawnEffectM;
    private static boolean effectSearched;
    private static final java.util.Random fxRand = new java.util.Random();

    public static boolean spawnHitParticles(double cx, double cy, double cz,
                                            double w, double h, int count, int particleId) {
        if (!effectSearched) { effectSearched = true; discoverEffectRenderer(); }
        if (effectRenderer == null || spawnEffectM == null) return false;
        if (count < 1) count = 1; else if (count > 500) count = 500;
        double spread = Math.max(0.3, w * 0.6);
        try {
            int[] none = new int[0];
            for (int i = 0; i < count; i++) {
                double px = cx + (fxRand.nextDouble() - 0.5) * spread;
                double py = cy + (fxRand.nextDouble() - 0.5) * h * 0.6;
                double pz = cz + (fxRand.nextDouble() - 0.5) * spread;
                // higher initial velocity = particles burst/scatter outward more
                double mx = fxRand.nextGaussian() * 0.38;
                double my = fxRand.nextGaussian() * 0.38 + 0.15; // slight upward bias
                double mz = fxRand.nextGaussian() * 0.38;
                spawnEffectM.invoke(effectRenderer, Integer.valueOf(particleId),
                        Double.valueOf(px), Double.valueOf(py), Double.valueOf(pz),
                        Double.valueOf(mx), Double.valueOf(my), Double.valueOf(mz), none);
            }
            return true;
        } catch (Throwable t) {
            spawnEffectM = null; // wrong method pinned — stop trying
            System.out.println("[IEA] HitParticles spawn error: " + t);
            return false;
        }
    }

    private static void discoverEffectRenderer() {
        if (minecraft == null) { effectSearched = false; return; } // retry once mc is ready
        try {
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                Method m = findSpawnEffect(t);
                if (m == null) continue;
                f.setAccessible(true);
                Object v = f.get(minecraft);
                if (v == null) continue;
                effectRenderer = v; spawnEffectM = m;
                System.out.println("[IEA] EffectRenderer = " + t.getName() + " spawn=" + m.getName());
                return;
            }
            System.out.println("[IEA] EffectRenderer not found (HitParticles inactive)");
        } catch (Throwable t) {
            System.out.println("[IEA] EffectRenderer discovery error: " + t);
        }
    }

    // spawnEffectParticle = the only (int, double x6, int[]) -> non-void method
    private static Method findSpawnEffect(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 8 || p[0] != int.class) continue;
            if (p[1] != double.class || p[2] != double.class || p[3] != double.class
                    || p[4] != double.class || p[5] != double.class || p[6] != double.class) continue;
            if (!p[7].isArray() || p[7].getComponentType() != int.class) continue;
            if (m.getReturnType().isPrimitive()) continue; // returns EntityFX (object)
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    // --- Hitbox: classify an entity using Minecraft's OWN entity registry ----
    // The Hitbox module renders each entity's real (vanilla) bounding box, coloured by
    // category (enemy / teammate / mob / arrow / pearl / projectile / other). We get the
    // category from EntityList's class->name map: those name strings ("Arrow",
    // "ThrownEnderpearl", "Snowball", ...) are NOT obfuscated, so they are a stable,
    // reliable way to tell projectile types apart without Notch mappings.
    private static Map<?, ?> entityNameMap;     // Class -> String (EntityList.classToStringMapping)
    private static boolean entityNameSearched;
    private static int entityNameAttempts;
    @SuppressWarnings("unchecked")
    private static Map<?, ?> entityNameMap(Object sample) {
        if (entityNameSearched) return entityNameMap;
        if (++entityNameAttempts > 5) { entityNameSearched = true; return null; } // give up; full scans are costly
        try {
            ClassLoader cl = sample.getClass().getClassLoader();
            Field cf = ClassLoader.class.getDeclaredField("classes");
            cf.setAccessible(true);
            java.util.Vector<Class<?>> classes = (java.util.Vector<Class<?>>) cf.get(cl);
            Object[] snap;
            synchronized (classes) { snap = classes.toArray(); }
            for (Object o : snap) {
                Class<?> c = (Class<?>) o;
                Field[] fs;
                try { fs = c.getDeclaredFields(); } catch (Throwable t) { continue; }
                for (Field f : fs) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    Map<?, ?> m;
                    try { f.setAccessible(true); m = (Map<?, ?>) f.get(null); }
                    catch (Throwable t) { continue; }
                    if (m == null || m.isEmpty()) continue;
                    Map.Entry<?, ?> e = m.entrySet().iterator().next();
                    if ((e.getKey() instanceof Class) && (e.getValue() instanceof String)
                            && (m.containsValue("Arrow") || m.containsValue("Item")
                                || m.containsValue("Pig"))) {
                        entityNameMap = m;
                        entityNameSearched = true;
                        System.out.println("[IEA] hitbox: EntityList names = " + c.getName()
                                + "." + f.getName() + " (" + m.size() + ")");
                        return entityNameMap;
                    }
                }
            }
            System.out.println("[IEA] hitbox: EntityList map not found (will retry)");
        } catch (Throwable t) {
            System.out.println("[IEA] hitbox: EntityList scan failed: " + t);
            entityNameSearched = true; // reflection unavailable; stop trying
        }
        return entityNameMap;
    }

    /** De-obfuscated EntityList name for a non-player entity (e.g. "Arrow"), or null. */
    public static String entityTypeName(Object entity) {
        if (entity == null) return null;
        Map<?, ?> m = entityNameMap(entity);
        if (m == null) return null;
        Object s = m.get(entity.getClass());
        return (s instanceof String) ? (String) s : null;
    }

    // EntityLivingBase = the superclass of EntityPlayer (where the GameProfile getter lives).
    private static Class<?> livingClass;
    public static boolean isLiving(Object entity) {
        if (entity == null) return false;
        if (livingClass == null) {
            Object lp = thePlayer();
            if (lp != null) {
                playerProfile(lp); // resolves gameProfileM (declared on EntityPlayer)
                if (gameProfileM != null) {
                    livingClass = gameProfileM.getDeclaringClass().getSuperclass();
                    System.out.println("[IEA] hitbox: EntityLivingBase = " + livingClass.getName());
                }
            }
        }
        return livingClass != null && livingClass.isInstance(entity);
    }

    // Entity.getLookVec() -> Vec3 (3 doubles). There can be several no-arg Vec3 getters
    // (getLookVec, getPositionVector); the look vector is the unit-length one, so we pick
    // the candidate whose result has magnitude ~1.
    private static Method lookVecM;
    private static Field[] vecFields;
    private static boolean lookSearched;
    public static double[] lookVec(Object entity) {
        if (entity == null) return null;
        try {
            if (!lookSearched) {
                lookSearched = true;
                for (Class<?> c = entity.getClass(); c != null && lookVecM == null; c = c.getSuperclass()) {
                    for (Method mm : c.getDeclaredMethods()) {
                        if (mm.getParameterTypes().length != 0) continue;
                        Class<?> rt = mm.getReturnType();
                        if (rt.isPrimitive() || rt.isArray()) continue;
                        Field[] vf = vec3Fields(rt);
                        if (vf == null) continue;
                        try {
                            mm.setAccessible(true);
                            Object v = mm.invoke(entity);
                            if (v == null) continue;
                            double dx = vf[0].getDouble(v), dy = vf[1].getDouble(v), dz = vf[2].getDouble(v);
                            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            // the look vector is unit length with every component in [-1,1];
                            // this rules out getPositionVector() (world coords).
                            boolean unitBox = Math.abs(dx) <= 1.01 && Math.abs(dy) <= 1.01 && Math.abs(dz) <= 1.01;
                            if (unitBox && len > 0.95 && len < 1.05) { lookVecM = mm; vecFields = vf; break; }
                        } catch (Throwable ignore) { }
                    }
                }
                System.out.println("[IEA] hitbox: getLookVec = "
                        + (lookVecM != null ? lookVecM.getName() : "?"));
            }
            if (lookVecM == null || vecFields == null) return null;
            Object v = lookVecM.invoke(entity);
            if (v == null) return null;
            return new double[] { vecFields[0].getDouble(v), vecFields[1].getDouble(v), vecFields[2].getDouble(v) };
        } catch (Throwable t) { return null; }
    }

    // Entity.getPositionVector() -> Vec3(posX,posY,posZ). It is the other no-arg Vec3 getter
    // besides getLookVec(); we pick the no-arg 3-double method that ISN'T the look vector.
    // Used to offset the AABB exactly like vanilla's F3+B box (handles off-centre boxes).
    private static Method posVecM;
    private static Field[] posVecFields;
    public static double[] entityPos(Object entity) {
        if (entity == null) return null;
        try {
            if (posVecM == null) {
                // The position lies INSIDE the entity's bounding box; the look vector (length ~1)
                // does not. Pick the no-arg Vec3 getter whose result is within the box.
                double[] b = aabb(entityBox(entity));
                for (Class<?> c = entity.getClass(); c != null && posVecM == null; c = c.getSuperclass()) {
                    for (Method mm : c.getDeclaredMethods()) {
                        if (mm.getParameterTypes().length != 0) continue;
                        Class<?> rt = mm.getReturnType();
                        if (rt.isPrimitive() || rt.isArray()) continue;
                        Field[] vf = vec3Fields(rt);
                        if (vf == null) continue;
                        try {
                            mm.setAccessible(true);
                            Object v = mm.invoke(entity);
                            if (v == null) continue;
                            double vx = vf[0].getDouble(v), vy = vf[1].getDouble(v), vz = vf[2].getDouble(v);
                            if (b != null && within(vx, b[0], b[3]) && within(vy, b[1], b[4])
                                    && within(vz, b[2], b[5])) {
                                posVecM = mm; posVecFields = vf; break;
                            }
                        } catch (Throwable ignore) { }
                    }
                }
                System.out.println("[IEA] hitbox: getPositionVector = "
                        + (posVecM != null ? posVecM.getName() : "?"));
            }
            if (posVecM == null) return null;
            Object v = posVecM.invoke(entity);
            if (v == null) return null;
            return new double[] { posVecFields[0].getDouble(v), posVecFields[1].getDouble(v), posVecFields[2].getDouble(v) };
        } catch (Throwable t) { return null; }
    }

    private static boolean within(double v, double lo, double hi) {
        double e = 0.5; return v >= lo - e && v <= hi + e;
    }

    // ISound.getSoundLocation() -> ResourceLocation, whose toString is "domain:path"
    // (e.g. "minecraft:random.explode"). It is the only no-arg getter whose result toString
    // looks like "x:y", so we find it by that shape and cache its (obf) name. Returns the
    // path (after ':'), the de-obfuscated sound event name used by SoundTuner.
    private static String soundLocName;
    private static boolean soundLocMissing;
    public static String soundName(Object isound) {
        if (isound == null || soundLocMissing) return null;
        try {
            Class<?> c = isound.getClass();
            if (soundLocName == null) {
                for (Method mm : c.getMethods()) {
                    if (mm.getParameterTypes().length != 0) continue;
                    Class<?> rt = mm.getReturnType();
                    if (rt.isPrimitive() || rt.isArray() || rt == String.class) continue;
                    Object loc;
                    try { mm.setAccessible(true); loc = mm.invoke(isound); } catch (Throwable e) { continue; }
                    if (loc == null) continue;
                    String s = String.valueOf(loc);
                    int col = s.indexOf(':');
                    if (col > 0 && col < s.length() - 1 && s.indexOf(' ') < 0 && s.length() < 80) {
                        soundLocName = mm.getName();
                        System.out.println("[IEA] sound location getter = " + mm.getName() + " (" + s + ")");
                        break;
                    }
                }
                if (soundLocName == null) { soundLocMissing = true; return null; }
            }
            Object loc = c.getMethod(soundLocName).invoke(isound);
            if (loc == null) return null;
            String s = String.valueOf(loc);
            int col = s.indexOf(':');
            return col >= 0 ? s.substring(col + 1) : s;
        } catch (Throwable t) { return null; }
    }

    // A Vec3-like class = exactly three non-static double fields (and nothing else). Returns
    // the three fields (declared order) or null.
    private static Field[] vec3Fields(Class<?> rt) {
        List<Field> ds = new ArrayList<Field>();
        for (Field f : rt.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != double.class) return null; // any non-double instance field disqualifies
            f.setAccessible(true);
            ds.add(f);
        }
        if (ds.size() != 3) return null;
        return new Field[] { ds.get(0), ds.get(1), ds.get(2) };
    }

    // --- local player's username (DeathAlert teammate matching) -------------
    public static String localPlayerName() {
        Object p = thePlayer();
        if (p == null) return null;
        return profileName(playerProfile(p));
    }

    // --- Scoreboard teammate detection (Bedwars DeathAlert) -----------------
    // theWorld (a Minecraft obj field) declares getScoreboard() -> Scoreboard, the obf
    // class with >= 3 Map fields. We resolve the World field + getter once, then read the
    // live Scoreboard each call (handles re-joins / world swaps). Hypixel puts every
    // online player on their Bedwars team's scoreboard team for nametag colouring, so the
    // teamMemberships map (Map<playerName, Team>) groups your team — exactly the teammate
    // set we need, with no Notch names.
    private static Field worldField;
    private static Method sbGetterM;
    private static boolean sbResolved;
    private static int sbAttempts;

    private static Object currentScoreboard() {
        if (minecraft == null) return null;
        try {
            if (!sbResolved) {
                if (sbAttempts++ > 400) return null;
                resolveScoreboardPath();
                if (!sbResolved) return null;
            }
            Object world = worldField.get(minecraft);
            if (world == null) return null;
            return sbGetterM.invoke(world);
        } catch (Throwable t) { return null; }
    }

    private static void resolveScoreboardPath() {
        try {
            for (Field f : minecraft.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                f.setAccessible(true);
                Object world;
                try { world = f.get(minecraft); } catch (Throwable e) { continue; }
                if (world == null) continue;
                Method getSb = scoreboardGetter(world.getClass());
                if (getSb == null) continue;
                Object sb;
                try { sb = getSb.invoke(world); } catch (Throwable e) { continue; }
                if (sb == null) continue;
                worldField = f; sbGetterM = getSb; sbResolved = true;
                System.out.println("[IEA] scoreboard = " + sb.getClass().getName()
                        + " via " + world.getClass().getName() + "." + getSb.getName());
                return;
            }
        } catch (Throwable ignored) { }
    }

    // a no-arg method (anywhere in World's hierarchy) returning an obf class with >= 3 Maps
    private static Method scoreboardGetter(Class<?> worldClass) {
        for (Class<?> c = worldClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt.isPrimitive() || rt.isArray() || rt.getName().indexOf('.') >= 0) continue;
                if (mapFieldCount(rt) >= 3) { m.setAccessible(true); return m; }
            }
        }
        return null;
    }

    private static int mapFieldCount(Class<?> c) {
        int n = 0;
        for (Field f : c.getDeclaredFields())
            if (Map.class.isAssignableFrom(f.getType())) n++;
        return n;
    }

    // teamMemberships = Map<playerName, ScorePlayerTeam>. On Hypixel each player gets their
    // OWN scoreboard team (for nametag colouring), so "same team object" only ever yields
    // yourself — teammates must be grouped by the team's COLOUR (ScorePlayerTeam.chatFormat,
    // an EnumChatFormatting). Members of one Bedwars team share that colour enum.
    private static Map<?, ?> membershipMap(Object sb) {
        Map<?, ?> best = null; int bestN = -1;
        try {
            for (Field f : sb.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Map<?, ?> m;
                try { m = (Map<?, ?>) f.get(sb); } catch (Throwable e) { continue; }
                if (m == null || m.isEmpty()) continue;
                Map.Entry<?, ?> e0 = m.entrySet().iterator().next();
                Object k = e0.getKey(), v = e0.getValue();
                if (!(k instanceof String)) continue;
                if (v == null || v instanceof Map || v instanceof java.util.Collection) continue;
                if (v.getClass().getName().indexOf('.') >= 0) continue; // value must be an obf Team
                if (m.size() > bestN) { bestN = m.size(); best = m; } // teamMemberships is the biggest
            }
        } catch (Throwable ignored) { }
        return best;
    }

    // The team colour = ScorePlayerTeam.namePrefix (the nametag prefix). In a Bedwars match this
    // is the team colour code (e.g. "§a"); members of one team share it. We pick the String field
    // that contains a § and whose §-stripped value is SHORTEST — the prefix ("§a" -> "") beats the
    // display name ("§f<username>") and the suffix (guild tag). Cached from the local player's team.
    private static String teamColor(Object team) {
        if (team == null) return null;
        try {
            // Re-pick the namePrefix field EVERY call (don't cache): early in a match a team's
            // namePrefix can still be empty, and caching then would lock onto displayName
            // ("§f<name>") — making every player look the same colour (all "teammates").
            String prefix = null; int bestLen = Integer.MAX_VALUE;
            for (Field f : team.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(team);
                if (!(v instanceof String) || ((String) v).indexOf('§') < 0) continue;
                int len = stripFormatting((String) v).length();
                if (len < bestLen) { bestLen = len; prefix = (String) v; } // "§a" beats "§f<name>"
            }
            if (prefix == null) return null;
            String c = null; // last § colour code in the prefix
            for (int i = 0; i + 1 < prefix.length(); i++)
                if (prefix.charAt(i) == '§') c = String.valueOf(Character.toLowerCase(prefix.charAt(i + 1)));
            return c;
        } catch (Throwable t) { return null; }
    }

    /** The local player's scoreboard nametag colour code (team colour in a match, rank colour
     *  in a lobby), e.g. "a" — or null. Used to colour the self-nametag like vanilla does. */
    public static String myTeamColor() {
        try {
            Object sb = currentScoreboard();
            if (sb == null) return null;
            String me = localPlayerName();
            if (me == null) return null;
            Map<?, ?> map = membershipMapFor(sb, me);
            if (map == null) return null;
            Object myTeam = map.get(me);
            return (myTeam == null) ? null : teamColor(myTeam);
        } catch (Throwable t) { return null; }
    }

    /** Usernames sharing the local player's Bedwars team colour (self included). Empty when
     *  solo / no team / not in a world. */
    public static java.util.Set<String> teammates() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        Object sb = currentScoreboard();
        if (sb == null) return out;
        String me = localPlayerName();
        if (me == null) return out;
        try {
            Map<?, ?> map = membershipMapFor(sb, me);
            if (map == null) return out;
            Object myTeam = map.get(me);
            if (myTeam == null) return out;
            out.add(me);
            String myColor = teamColor(myTeam);
            if (myColor == null) return out; // no colour prefix discovered
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) continue;
                String c = teamColor(e.getValue());
                if (myColor.equals(c)) out.add((String) e.getKey());
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // the String->Team map that CONTAINS the local player's name as a key = teamMemberships
    // (the 'teams' map is keyed by team name and won't contain us). Falls back to the biggest.
    private static Map<?, ?> membershipMapFor(Object sb, String me) {
        Map<?, ?> fallback = null; int bestN = -1;
        try {
            for (Field f : sb.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Map<?, ?> m;
                try { m = (Map<?, ?>) f.get(sb); } catch (Throwable e) { continue; }
                if (m == null || m.isEmpty()) continue;
                Map.Entry<?, ?> e0 = m.entrySet().iterator().next();
                Object k = e0.getKey(), v = e0.getValue();
                if (!(k instanceof String)) continue;
                if (v == null || v instanceof Map || v instanceof java.util.Collection) continue;
                if (v.getClass().getName().indexOf('.') >= 0) continue; // value must be an obf Team
                if (m.containsKey(me)) return m;                        // teamMemberships
                if (m.size() > bestN) { bestN = m.size(); fallback = m; }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    // Diagnostic: print every ScorePlayerTeam field (name=value) for the local player and a few
    // others, so we can confirm which field encodes the Bedwars team. Runs only inside a match
    // (tab list 2..16), up to a few times spaced apart, so it captures the match (not the lobby).
    private static int teamDumps;
    private static long lastTeamDumpMs;
    public static void dumpTeamInfo() {
        if (teamDumps >= 3) return;
        int tab = tabListNames().size();
        if (tab < 2 || tab > 16) return; // lobby/limbo, not a match
        long now = System.currentTimeMillis();
        if (now - lastTeamDumpMs < 6000) return;
        Object sb = currentScoreboard();
        if (sb == null) return;
        String me = localPlayerName();
        Map<?, ?> map = membershipMapFor(sb, me);
        if (map == null || map.isEmpty()) return;
        lastTeamDumpMs = now; teamDumps++;
        try {
            int n = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) continue;
                String name = (String) e.getKey();
                boolean isMe = name.equals(me);
                if (!isMe && n >= 6) continue;
                n++;
                Object team = e.getValue();
                StringBuilder vb = new StringBuilder();
                for (Field f : team.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t == String.class || t.isEnum() || t == boolean.class || t == int.class) {
                        f.setAccessible(true);
                        Object v; try { v = f.get(team); } catch (Throwable x) { v = "?"; }
                        String vs = v == null ? "null" : v.toString().replace('§', '&');
                        vb.append(f.getName()).append('=').append(vs).append("  ");
                    }
                }
                System.out.println("[IEA][teamdump]" + (isMe ? "*ME* " : " ") + name + " => " + vb);
            }
        } catch (Throwable t) { System.out.println("[IEA][teamdump] err " + t); }
    }

    /** Usernames currently in the tab player list (NetHandler.playerInfoMap). Reuses the
     *  NetHandler / info-map / GameProfile fields discovered for Ping. Empty when offline. */
    public static java.util.Set<String> tabListNames() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        Object p = thePlayer();
        if (p == null) return out;
        try {
            if (!pingSearched) { pingSearched = true; discoverPing(p); }
            if (netHandlerField == null || infoMapField == null) return out;
            Object nh = netHandlerField.get(p);
            if (nh == null) return out;
            Map<?, ?> map = (Map<?, ?>) infoMapField.get(nh);
            if (map == null) return out;
            for (Object v : map.values()) {
                if (v == null) continue;
                if (infoProfileField == null) {
                    for (Field f : v.getClass().getDeclaredFields())
                        if (f.getType().getName().equals("com.mojang.authlib.GameProfile")) {
                            f.setAccessible(true); infoProfileField = f; break;
                        }
                    if (infoProfileField == null) break;
                }
                String name = profileName(infoProfileField.get(v));
                if (name != null) out.add(name);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Every player name registered on a scoreboard team (= everyone in the game). The
     *  teamMemberships map (String name -> Team) is the largest String->Team map. */
    public static java.util.Set<String> allTeamMembers() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        Object sb = currentScoreboard();
        if (sb == null) return out;
        Map<?, ?> map = membershipMap(sb);
        if (map != null) for (Object k : map.keySet()) if (k instanceof String) out.add((String) k);
        return out;
    }

    // --- chat reading (DeathAlert) -----------------------------------------
    // GuiNewChat = an object reachable from a Minecraft obj field (GuiIngame) whose class
    // has a Minecraft-typed field + >= 2 List fields. Its first List<ChatLine> field is
    // chatLines (one entry per received message, newest at index 0); ChatLine holds one
    // IChatComponent. We read the message via the component's no-arg String getters,
    // taking the longest (= full text incl. children), then strip the § colour codes.
    private static Object guiNewChat;
    private static Field chatLinesField, chatLineCompField;
    private static Method[] compTextMethods;
    private static boolean chatResolved;
    private static int chatAttempts;
    private static Object lastTopChatLine; // newest line we've already returned (identity)

    private static void resolveChat() {
        if (chatResolved || minecraft == null || chatAttempts++ > 400) return;
        try {
            Class<?> mcClass = minecraft.getClass();
            for (Field f : mcClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
                f.setAccessible(true);
                Object gi;
                try { gi = f.get(minecraft); } catch (Throwable e) { continue; }
                if (gi == null) continue;
                for (Field g : gi.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(g.getModifiers())) continue;
                    Class<?> gt = g.getType();
                    if (gt.isPrimitive() || gt.isArray() || gt.getName().indexOf('.') >= 0) continue;
                    if (!isGuiNewChat(gt, mcClass)) continue;
                    g.setAccessible(true);
                    Object cand;
                    try { cand = g.get(gi); } catch (Throwable e) { continue; }
                    if (cand != null && setupChat(cand)) {
                        guiNewChat = cand; chatResolved = true;
                        System.out.println("[IEA] GuiNewChat = " + cand.getClass().getName()
                                + " chatLines=" + chatLinesField.getName()
                                + " comp=" + chatLineCompField.getName()
                                + " textGetters=" + (compTextMethods == null ? 0 : compTextMethods.length));
                        return;
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean isGuiNewChat(Class<?> c, Class<?> mcClass) {
        boolean mcRef = false; int lists = 0;
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t == mcClass) mcRef = true;
            else if (List.class.isAssignableFrom(t)) lists++;
        }
        return mcRef && lists >= 2;
    }

    private static boolean setupChat(Object chat) {
        try {
            for (Field f : chat.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Class<?> elem = listElementType(f);
                if (elem == null) { // generics stripped — peek a live element
                    Object live = f.get(chat);
                    if (live instanceof List && !((List<?>) live).isEmpty()) {
                        Object e0 = ((List<?>) live).get(0);
                        if (e0 != null) elem = e0.getClass();
                    }
                }
                if (elem == null || elem.getName().indexOf('.') >= 0) continue; // need obf ChatLine
                Field comp = componentField(elem);
                if (comp == null) continue;
                Method[] tms = componentTextMethods(comp.getType());
                if (tms.length == 0) continue;
                chatLinesField = f; chatLineCompField = comp; compTextMethods = tms;
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Class<?> listElementType(Field f) {
        try {
            java.lang.reflect.Type gt = f.getGenericType();
            if (gt instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.Type[] a = ((java.lang.reflect.ParameterizedType) gt).getActualTypeArguments();
                if (a.length == 1 && a[0] instanceof Class) return (Class<?>) a[0];
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ChatLine.lineString = its only non-primitive obf field whose type exposes a no-arg
    // String getter (the IChatComponent interface).
    private static Field componentField(Class<?> chatLine) {
        for (Field f : chatLine.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (t.isPrimitive() || t.isArray() || t.getName().indexOf('.') >= 0) continue;
            if (componentTextMethods(t).length > 0) { f.setAccessible(true); return f; }
        }
        return null;
    }

    // all no-arg String methods declared on the IChatComponent interface (+ super-ifaces):
    // getUnformattedText / getFormattedText / getUnformattedTextForChat. We later call all
    // and keep the longest result, so we never depend on pinning the exact obf name.
    private static Method[] componentTextMethods(Class<?> t) {
        java.util.List<Method> ms = new ArrayList<Method>();
        collectTextMethods(t, ms);
        return ms.toArray(new Method[ms.size()]);
    }

    private static void collectTextMethods(Class<?> t, java.util.List<Method> out) {
        if (t == null) return;
        for (Method m : t.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class) {
                m.setAccessible(true); out.add(m);
            }
        }
        for (Class<?> s : t.getInterfaces()) collectTextMethods(s, out);
    }

    /** Plain text of chat messages received since the last call, newest first; § stripped.
     *  The first call after (re)resolving seeds the marker and returns empty (no backlog). */
    public static java.util.List<String> newChatLines() {
        java.util.List<String> out = new ArrayList<String>();
        resolveChat();
        if (!chatResolved || guiNewChat == null) return out;
        try {
            Object v = chatLinesField.get(guiNewChat);
            if (!(v instanceof List)) return out;
            List<?> lines = (List<?>) v;
            if (lines.isEmpty()) return out;
            if (lastTopChatLine == null) { lastTopChatLine = lines.get(0); return out; } // seed
            Object newTop = lines.get(0);
            int limit = Math.min(lines.size(), 40);
            for (int i = 0; i < limit; i++) {
                Object line = lines.get(i);
                if (line == lastTopChatLine) break;
                String text = lineText(line);
                if (text != null) out.add(text);
            }
            lastTopChatLine = newTop;
        } catch (Throwable ignored) { }
        return out;
    }

    private static String lineText(Object line) {
        try {
            Object comp = chatLineCompField.get(line);
            if (comp == null) return null;
            String best = null;
            for (Method m : compTextMethods) {
                Object r;
                try { r = m.invoke(comp); } catch (Throwable e) { continue; }
                if (r instanceof String) {
                    String s = stripFormatting((String) r);
                    if (best == null || s.length() > best.length()) best = s;
                }
            }
            return best;
        } catch (Throwable t) { return null; }
    }

    /** Remove Minecraft § colour/format codes. */
    public static String stripFormatting(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            b.append(c);
        }
        return b.toString();
    }
}
