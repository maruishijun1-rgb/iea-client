package dev.iea.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Injects hooks into LWJGL (un-obfuscated, no Minecraft mappings needed):
 *   Display.update()                 -> Hook.onFrame() at entry
 *   Mouse.getDX()                    -> Hook.filterDX(ret)    (camera block + virtual cursor)
 *   Mouse.getDY()                    -> Hook.filterDY(ret)
 *   Mouse.getDWheel()                -> Hook.filterWheel(ret)
 *   Mouse.next() / Keyboard.next()   -> Hook.filterEvent(ret)
 *   Mouse.setGrabbed(boolean)        -> arg = Hook.filterGrab(arg) at entry
 * All injections are linear (no branches) -> no stack-map-frame handling needed.
 */
public final class Transformer implements ClassFileTransformer {
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final String MOUSE = "org/lwjgl/input/Mouse";
    private static final String KEYBOARD = "org/lwjgl/input/Keyboard";
    private static final String HOOK = "dev/iea/client/Hook";
    // RenderGlobal (1.8.9 obfuscated). drawSelectionBoundingBox(AxisAlignedBB) = a(aug)V.
    private static final String RENDERGLOBAL = "bfr";
    // RenderManager. doRenderEntity(Entity,double,double,double,float,float,boolean) = a(pk,DDD,FF,Z)Z.
    private static final String RENDERMANAGER = "biu";
    // ItemRenderer. We wrap every (float)->void method (renderItemInFirstPerson is one
    // of them) so the OldAnimations "swing while using" hook can briefly hide the
    // item-in-use state and let vanilla draw the swing instead of the eat/block pose.
    private static final String ITEMRENDERER = "bfn";
    // EntityPlayer / Entity: we wrap every ()->float getter so the OldAnimations
    // "smooth sneak" hook can intercept getEyeHeight() and return a smoothly
    // interpolated value (the local player's camera height eases instead of snapping).
    private static final String ENTITYPLAYER = "wn";
    private static final String ENTITY = "pk";
    // Minecraft. drawSplashScreen(TextureManager) = a(bmj)V — calls updateDisplay()
    // internally, so our onFrame fires while it's showing the Mojang logo; we use that
    // to paint the IEA logo over it.
    private static final String MINECRAFT = "ave";
    // GuiButton. drawButton(Minecraft, mouseX, mouseY) = a(ave,int,int)V. We REPLACE the
    // whole body with our own draw so every vanilla button matches the ClickGui style.
    private static final String GUIBUTTON = "avs";
    // GuiIngame. showCrosshair() is its only ()->boolean method, b()Z. We filter its
    // return value so the vanilla crosshair hides while our Crosshair module is on.
    private static final String GUIINGAME = "avo";
    // PlayerControllerMP. attackEntity(EntityPlayer, Entity) = a(wn,pk)V — entry hook
    // records the reach distance of each hit for the Reach HUD.
    private static final String PLAYERCONTROLLER = "bda";
    // EntityRenderer. getFOVModifier(partialTicks, useFOVSetting) = a(FZ)F — Zoom
    // divides its return value (render-side zoom; gameSettings is never written).
    private static final String ENTITYRENDERER = "bfk";
    // Gui (base). drawTexturedModalRect(x,y,tX,tY,w,h) = (IIIIII)V — the stat-bars
    // option suppresses the vanilla heart/hunger/armor 9x9 sprites through it.
    private static final String GUI = "avp";
    // FontRenderer. The IEAFont module replaces drawString(String,F,F,I,Z)I (the
    // funnel every draw goes through) plus getStringWidth/getCharWidth so all layout
    // math stays consistent with the replacement font.
    private static final String FONTRENDERER = "avn";
    // GuiMainMenu.drawScreen = aya.a(IIF)V. We replace its body with: draw our
    // cobblestone background + IEA logo, then call super (GuiScreen.drawScreen) for the
    // buttons. This drops the panorama / Mojang title / splash text.
    private static final String GUIMAINMENU = "aya";
    private static final String GUISCREEN = "axu"; // super of GuiMainMenu; drawScreen = a(IIF)V
    // RendererLivingEntity. setBrightness(EntityLivingBase pr, float, boolean) = a(pr;FZ)Z —
    // on EXIT, when it applied the hurt overlay we re-tint glColor for the HitColor module.
    private static final String RENDERLIVING = "bjl";
    // SoundManager is the obf class that has a field of the (un-obfuscated) paulscode
    // SoundSystem type. We detect it by that reference — no hard-coded mapping needed — and
    // scale getNormalizedVolume()'s result for the per-sound SoundTuner module.
    private static final String SOUNDSYS = "paulscode/sound/SoundSystem";
    // Render.renderLivingLabel(Entity, String, double,double,double, int) draws the nametag.
    // We match it by its exact descriptor (no mapping needed) and rewrite the name string.
    private static final String NAMETAG_DESC = "(L" + ENTITY + ";Ljava/lang/String;DDDI)V";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] bytes) {
        if (name == null) return null;
        // MC obf classes are in the default package (no '/'); only scan those for the refs.
        final boolean mcClass = name.indexOf('/') < 0;
        final boolean soundCand = mcClass && containsBytes(bytes, SOUNDSYS);
        final boolean nameTagCand = mcClass && containsBytes(bytes, NAMETAG_DESC);
        if (!soundCand && !nameTagCand
                && !name.equals(DISPLAY) && !name.equals(MOUSE) && !name.equals(KEYBOARD)
                && !name.equals(RENDERGLOBAL) && !name.equals(RENDERMANAGER)
                && !name.equals(ITEMRENDERER) && !name.equals(ENTITYPLAYER)
                && !name.equals(ENTITY) && !name.equals(MINECRAFT)
                && !name.equals(GUIBUTTON) && !name.equals(GUIMAINMENU)
                && !name.equals(GUIINGAME) && !name.equals(PLAYERCONTROLLER)
                && !name.equals(ENTITYRENDERER) && !name.equals(GUI)
                && !name.equals(FONTRENDERER) && !name.equals(RENDERLIVING)) return null;
        try {
            final String cn = name;
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String mName, String desc,
                                                 String sig, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, mName, desc, sig, exceptions);

                    // GuiMainMenu.drawScreen / GuiButton.drawButton: guarded at entry on
                    // Hook.ieaGuiOn(). When the IEAGui module is ON we run the IEA draw and
                    // RETURN; when OFF we fall through to the UNCHANGED vanilla body, so the
                    // panorama / Minecraft logo / stock buttons come back exactly as vanilla.
                    final boolean mainMenu = cn.equals(GUIMAINMENU) && mName.equals("a") && desc.equals("(IIF)V");
                    final boolean guiButton = cn.equals(GUIBUTTON) && mName.equals("a") && desc.equals("(Lave;II)V");

                    final boolean onFrame = cn.equals(DISPLAY) && mName.equals("update") && desc.equals("()V");
                    final boolean grab = cn.equals(MOUSE) && mName.equals("setGrabbed") && desc.equals("(Z)V");
                    final boolean selBox = cn.equals(RENDERGLOBAL) && mName.equals("a") && desc.equals("(Laug;)V");
                    final boolean entityRender = cn.equals(RENDERMANAGER) && mName.equals("a")
                            && desc.equals("(Lpk;DDDFFZ)Z");
                    // wrap every (float)->void on ItemRenderer (renderItemInFirstPerson is one)
                    final boolean itemRenderFv = cn.equals(ITEMRENDERER) && desc.equals("(F)V");
                    // transformFirstPersonItem(equipProgress, swingProgress) is (FF)V; we
                    // rewrite its swing arg so the swing plays on top of the use pose.
                    final boolean itemTransform = cn.equals(ITEMRENDERER) && desc.equals("(FF)V");
                    // every ()->float on the player/entity (getEyeHeight is one) -> smooth-sneak filter
                    final boolean eyeMethod = (cn.equals(ENTITYPLAYER) || cn.equals(ENTITY))
                            && desc.equals("()F");
                    // attackEntity(player, target) -> record reach for the Reach HUD
                    final boolean attack = cn.equals(PLAYERCONTROLLER) && mName.equals("a")
                            && desc.equals("(Lwn;Lpk;)V");
                    // RendererLivingEntity.setBrightness exit -> HitColor re-tint
                    final boolean setBright = cn.equals(RENDERLIVING) && mName.equals("a")
                            && desc.equals("(Lpr;FZ)Z");
                    // RendererLivingEntity.canRenderName(EntityLivingBase)Z -> force the local
                    // player's own nametag on (vanilla suppresses it); pitch is fixed separately.
                    final boolean canRenderName = cn.equals(RENDERLIVING) && desc.equals("(Lpr;)Z");
                    if (canRenderName) System.out.println("[IEA] canRenderName hook = " + cn + "." + mName + desc);
                    // RenderGlobal.renderSky candidate (one of three (FI)V; confirm in-game).
                    // When CustomSky is on we draw our sky and return, else vanilla runs.
                    final boolean renderSky = cn.equals(RENDERGLOBAL) && mName.equals("a")
                            && desc.equals("(FI)V");
                    // getFOVModifier -> divide the returned FOV by the zoom factor
                    final boolean fovMethod = cn.equals(ENTITYRENDERER) && mName.equals("a")
                            && desc.equals("(FZ)F");
                    // renderHotbar(ScaledResolution, float): if Hook.drawHotbar handles it
                    // (IEA-styled hotbar), skip the vanilla body
                    final boolean hotbar = cn.equals(GUIINGAME) && mName.equals("a")
                            && desc.equals("(Lavr;F)V");
                    // Gui.drawTexturedModalRect: skip the heart/hunger/armor sprites
                    // when the stat-bars option replaces them
                    final boolean statIcon = cn.equals(GUI) && desc.equals("(IIIIII)V");
                    // FontRenderer: drawString funnel + width metrics (IEAFont module)
                    final boolean fontDraw = cn.equals(FONTRENDERER) && mName.equals("a")
                            && desc.equals("(Ljava/lang/String;FFIZ)I");
                    final boolean fontWidthM = cn.equals(FONTRENDERER) && mName.equals("a")
                            && desc.equals("(Ljava/lang/String;)I");
                    final boolean fontCharM = cn.equals(FONTRENDERER) && mName.equals("a")
                            && desc.equals("(C)I");
                    // drawSplashScreen(TextureManager) -> flag the IEA splash overlay
                    final boolean splash = cn.equals(MINECRAFT) && mName.equals("a")
                            && desc.equals("(Lbmj;)V");
                    String ef = null, ed = null;
                    if (cn.equals(MOUSE) && desc.equals("()I")) {
                        if (mName.equals("getDX")) ef = "filterDX";
                        else if (mName.equals("getDY")) ef = "filterDY";
                        else if (mName.equals("getDWheel")) ef = "filterWheel";
                        if (ef != null) ed = "(I)I";
                    } else if (cn.equals(MOUSE) && mName.equals("next") && desc.equals("()Z")) {
                        ef = "filterEvent"; ed = "(Z)Z";
                    } else if (cn.equals(KEYBOARD) && mName.equals("next") && desc.equals("()Z")) {
                        // capture keyboard events for the search box so none are dropped
                        ef = "filterKeyEvent"; ed = "(Z)Z";
                    } else if (cn.equals(GUIINGAME) && mName.equals("b") && desc.equals("()Z")) {
                        ef = "filterShowCrosshair"; ed = "(Z)Z"; // hide vanilla crosshair
                    }
                    final String exitFilter = ef, exitDesc = ed;
                    // SoundManager.getNormalizedVolume(ISound,SoundPoolEntry,SoundCategory)F is the
                    // only (obj,obj,obj)->float method; scale its result per sound on exit.
                    final boolean soundVol = soundCand && threeObjFloat(desc);
                    if (soundVol) System.out.println("[IEA] sound volume hook = " + cn + "." + mName + desc);
                    final boolean nameTag = nameTagCand && desc.equals(NAMETAG_DESC);
                    if (nameTag) System.out.println("[IEA] nametag hook = " + cn + "." + mName + desc);
                    if (!onFrame && !grab && !selBox && !entityRender && !itemRenderFv
                            && !itemTransform && !eyeMethod && !splash && !attack
                            && !fovMethod && !hotbar && !statIcon && !fontDraw && !fontWidthM
                            && !fontCharM && !mainMenu && !guiButton && !setBright && !renderSky
                            && !soundVol && !nameTag && !canRenderName && exitFilter == null) return mv;

                    return new AdviceAdapter(Opcodes.ASM5, mv, access, mName, desc) {
                        @Override
                        protected void onMethodEnter() {
                            if (mainMenu) { // IEAGui on: IEA bg + super(buttons) + return; off: vanilla body
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "ieaGuiOn", "()Z", false);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IFEQ, cont);
                                loadThis();
                                loadArg(0); loadArg(1); loadArg(2);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "drawMainMenu",
                                        "(Ljava/lang/Object;IIF)V", false);
                                loadThis();
                                loadArg(0); loadArg(1); loadArg(2);
                                visitMethodInsn(Opcodes.INVOKESPECIAL, GUISCREEN, "a", "(IIF)V", false);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 4,
                                        new Object[] { GUIMAINMENU, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.FLOAT },
                                        0, new Object[0]);
                            }
                            if (guiButton) { // IEAGui on: IEA-styled draw + return; off: vanilla body
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "ieaGuiOn", "()Z", false);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IFEQ, cont);
                                loadThis();
                                loadArg(0); loadArg(1); loadArg(2);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "drawButton",
                                        "(Ljava/lang/Object;Ljava/lang/Object;II)V", false);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 4,
                                        new Object[] { GUIBUTTON, "ave", Opcodes.INTEGER, Opcodes.INTEGER },
                                        0, new Object[0]);
                            }
                            if (renderSky) { // CustomSky on: draw our sky + return; else vanilla
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "customSkyOn", "()Z", false);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IFEQ, cont);
                                loadArg(0); loadArg(1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "drawCustomSky", "(FI)V", false);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 3,
                                        new Object[] { RENDERGLOBAL, Opcodes.FLOAT, Opcodes.INTEGER },
                                        0, new Object[0]);
                            }
                            if (onFrame) visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onFrame", "()V", false);
                            if (grab) {
                                loadArg(0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "filterGrab", "(Z)Z", false);
                                storeArg(0);
                            }
                            if (selBox) { // draw our colored overlay before the vanilla outline
                                loadArg(0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onSelectionBox", "(Ljava/lang/Object;)V", false);
                            }
                            if (nameTag) { // LevelHead: prepend a level tag to the nametag string
                                loadArg(0); // entity
                                loadArg(1); // name
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "filterNameTag",
                                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);
                                storeArg(1);
                            }
                            if (entityRender) {
                                // Hitbox (vanilla AABB, per-category colour) + TNT timer + LevelHead,
                                // all anchored at the entity's interpolated render position.
                                loadArg(0); // entity
                                loadArg(1); // x
                                loadArg(2); // y
                                loadArg(3); // z
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onEntityRender",
                                        "(Ljava/lang/Object;DDD)V", false);
                            }
                            if (itemRenderFv) { // OldAnimations equip: keep item raised during the draw
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "itemRenderEnter", "()V", false);
                            }
                            if (itemTransform) { // OldAnimations swing: rewrite the swing arg
                                loadArg(0);
                                loadArg(1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "swingArg", "(FF)F", false);
                                storeArg(1);
                            }
                            if (splash) { // flag the Mojang splash so onFrame paints the IEA logo
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onSplash", "()V", false);
                            }
                            if (attack) { // record reach distance of this hit
                                loadArg(0); // player
                                loadArg(1); // target
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onAttack",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                            if (hotbar) { // IEA hotbar: if our draw handled it, skip vanilla
                                loadThis();
                                loadArg(0); // ScaledResolution
                                loadArg(1); // partialTicks
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "drawHotbar",
                                        "(Ljava/lang/Object;Ljava/lang/Object;F)Z", false);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IFEQ, cont);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 3,
                                        new Object[] { "avo", "avr", Opcodes.FLOAT }, 0, new Object[0]);
                            }
                            if (fontDraw) { // IEAFont: replacement returns end-x, or -1 = vanilla
                                loadThis();
                                loadArg(0); loadArg(1); loadArg(2); loadArg(3); loadArg(4);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fontDrawString",
                                        "(Ljava/lang/Object;Ljava/lang/String;FFIZ)I", false);
                                visitInsn(Opcodes.DUP);
                                visitInsn(Opcodes.ICONST_M1);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IF_ICMPEQ, cont);
                                visitInsn(Opcodes.IRETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 6,
                                        new Object[] { FONTRENDERER, "java/lang/String", Opcodes.FLOAT,
                                                Opcodes.FLOAT, Opcodes.INTEGER, Opcodes.INTEGER },
                                        1, new Object[] { Opcodes.INTEGER });
                                visitInsn(Opcodes.POP);
                            }
                            if (fontWidthM) { // IEAFont: replacement width, or -1 = vanilla
                                loadThis();
                                loadArg(0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fontWidth",
                                        "(Ljava/lang/Object;Ljava/lang/String;)I", false);
                                visitInsn(Opcodes.DUP);
                                visitInsn(Opcodes.ICONST_M1);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IF_ICMPEQ, cont);
                                visitInsn(Opcodes.IRETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 2,
                                        new Object[] { FONTRENDERER, "java/lang/String" },
                                        1, new Object[] { Opcodes.INTEGER });
                                visitInsn(Opcodes.POP);
                            }
                            if (fontCharM) { // IEAFont: replacement char advance, or -1 = vanilla
                                loadArg(0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "fontCharWidth", "(C)I", false);
                                visitInsn(Opcodes.DUP);
                                visitInsn(Opcodes.ICONST_M1);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IF_ICMPEQ, cont);
                                visitInsn(Opcodes.IRETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 2,
                                        new Object[] { FONTRENDERER, Opcodes.INTEGER },
                                        1, new Object[] { Opcodes.INTEGER });
                                visitInsn(Opcodes.POP);
                            }
                            if (statIcon) { // skip vanilla heart/hunger/armor sprites
                                loadArg(2); // textureX
                                loadArg(3); // textureY
                                loadArg(4); // width
                                loadArg(5); // height
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "filterStatIcon",
                                        "(IIII)Z", false);
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IFEQ, cont);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_NEW, 7,
                                        new Object[] { GUI, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER,
                                                Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER },
                                        0, new Object[0]);
                            }
                        }

                        @Override
                        protected void onMethodExit(int opcode) {
                            if (itemRenderFv) {
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "itemRenderExit", "()V", false);
                            }
                            if (entityRender && opcode == IRETURN) {
                                // draw the Hitbox AFTER the entity's own model so it layers on top.
                                // (doRenderEntity returns boolean -> IRETURN; the result stays on the
                                // stack beneath our void call's args, so it is returned unchanged.)
                                loadArg(0); loadArg(1); loadArg(2); loadArg(3);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onEntityHitbox",
                                        "(Ljava/lang/Object;DDD)V", false);
                            }
                            if (eyeMethod && opcode == FRETURN) {
                                // stack: [float ret]; push 'this' -> eyeHeightFilter(ret, this)
                                loadThis();
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "eyeHeightFilter",
                                        "(FLjava/lang/Object;)F", false);
                            }
                            if (fovMethod && opcode == FRETURN) {
                                // stack: [float fov]; push useFOVSetting -> filterFov(fov, flag)
                                loadArg(1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "filterFov", "(FZ)F", false);
                            }
                            if (exitFilter != null && opcode == IRETURN) {
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, exitFilter, exitDesc, false);
                            }
                            if (soundVol && opcode == FRETURN) {
                                // stack: [float vol]; push the ISound (arg0) -> filterSoundVolume(vol, sound)
                                loadArg(0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "filterSoundVolume",
                                        "(FLjava/lang/Object;)F", false);
                            }
                            if (nameTag && opcode == RETURN) {
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "afterNameTag", "()V", false);
                            }
                            if (canRenderName && opcode == IRETURN) {
                                loadArg(0); // entity
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "filterRenderName",
                                        "(ZLjava/lang/Object;)Z", false);
                            }
                            if (setBright && opcode == IRETURN) {
                                // stack: [boolean applied]; dup it + pass the entity (arg0)
                                // so HitColor can re-tint glColor before the model draws
                                visitInsn(Opcodes.DUP);
                                loadArg(0);
                                visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "onSetBrightness",
                                        "(ZLjava/lang/Object;)V", false);
                            }
                        }
                    };
                }
            };
            // EXPAND_FRAMES is required because AdviceAdapter (LocalVariablesSorter)
            // only accepts expanded stack-map frames. Our injections are linear
            // (entry load+invokestatic / return-value filters), so frames pass
            // through unchanged and COMPUTE_MAXS handles the extra stack.
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            System.out.println("[IEA] transformed " + name);
            return cw.toByteArray();
        } catch (Throwable t) {
            System.out.println("[IEA] transform failed for " + name + ": " + t);
            return null;
        }
    }

    // True if the class bytes contain the given ASCII string (e.g. a referenced class name in
    // the constant pool). Cheap substring scan over the raw bytes.
    private static boolean containsBytes(byte[] b, String s) {
        byte[] t = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (t.length == 0 || b.length < t.length) return false;
        outer:
        for (int i = 0; i + t.length <= b.length; i++) {
            for (int j = 0; j < t.length; j++) if (b[i + j] != t[j]) continue outer;
            return true;
        }
        return false;
    }

    // A method descriptor with exactly three object params and a float return — uniquely
    // SoundManager.getNormalizedVolume in the sound class (getNormalizedPitch has two params).
    private static boolean threeObjFloat(String desc) {
        if (desc == null || !desc.endsWith(")F")) return false;
        int close = desc.indexOf(')');
        if (close < 0) return false;
        String params = desc.substring(1, close);
        int count = 0, i = 0;
        while (i < params.length()) {
            if (params.charAt(i) != 'L') return false; // primitive/array param -> not it
            int e = params.indexOf(';', i);
            if (e < 0) return false;
            i = e + 1;
            count++;
        }
        return count == 3;
    }
}
