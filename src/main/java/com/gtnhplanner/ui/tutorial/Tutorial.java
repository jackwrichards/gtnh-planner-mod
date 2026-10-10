package com.gtnhplanner.ui.tutorial;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.event.KeyboardInputEvent;
import com.cleanroommc.modularui.api.event.MouseInputEvent;
import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The tour: GTNH Planner showing itself, in the player's game. It starts from the "?" key on the board (or the
 * first-run notice), runs in plans of its own ({@link Sandbox}) and drives the screens with a pointer of its own
 * ({@link Pointer}, {@link VirtualInput}). Each beat acts something out, then a {@link Callout} beside it says what it
 * showed and waits for Next. The player's mouse works only the callout meanwhile (a click elsewhere hurries the beat
 * along); the right arrow, Space or Enter is Next, the left arrow Back, Esc leaves. {@link Script} is what it shows;
 * {@link Director} plays it.
 */
public final class Tutorial {

    private static Tutorial current;

    private final Sandbox sandbox = new Sandbox();
    private final Director director;
    private final Callout callout;
    private long lastDraw = -1;

    private Tutorial() {
        this(Script.beats());
    }

    private Tutorial(final java.util.List<Tour.Beat> beats) {
        director = new Director(beats, sandbox);
        callout = new Callout(director, Tutorial::stop);
    }

    /** Whether the tour is running. */
    public static boolean active() {
        return current != null;
    }

    /** Whether the tour is hurrying through a step or catching up to one: too fast for its sounds to be heard. */
    public static boolean hurried() {
        final Tutorial t = current;
        return t != null && (t.director.hurrying() || t.director.catchingUp());
    }

    /** Starts the tour from the top (in a world only). */
    public static void start() {
        if (current == null) startAt(0);
    }

    /**
     * The dev harness, filming: plays {@link Film} (a build for the trailer) in place of the tour, the same way and in
     * the same sandbox. Stopped like the tour.
     */
    public static void startFilm() {
        if (current != null || Minecraft.getMinecraft().theWorld == null) return;
        begin(() -> new Tutorial(Film.beats()), 0);
    }

    /**
     * The dev harness, filming: a finished plan built again by the tour's cursor ({@link Rebuild}), a take a seed (the
     * same seed, the same take). Stopped like the tour.
     */
    public static void startRebuild(final com.gtnhplanner.data.flowchart.Graph plan, final long seed) {
        if (current != null || Minecraft.getMinecraft().theWorld == null) return;
        begin(() -> new Tutorial(Rebuild.beats(plan, seed)), 0);
    }

    /** Starts the tour at a beat (the dev harness), or goes there when it is running. */
    public static void startAt(final int beat) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        if (current != null) {
            current.director.goTo(beat);
            return;
        }
        com.gtnhplanner.ui.PlannerSettings.setTourOffered(true);
        begin(Tutorial::new, beat);
    }

    private static void begin(final java.util.function.Supplier<Tutorial> make, final int beat) {
        try {
            current = make.get();
            current.sandbox.enter();
            GtnhPlanner.LOG.info("[tutorial] started");
            current.director.goTo(beat);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.error("[tutorial] could not start", e);
            stop();
        }
    }

    /** Ends the tour: everything put back, the player where they were. */
    public static void stop() {
        final Tutorial t = current;
        if (t == null) return;
        current = null;
        t.director.release();
        try {
            t.sandbox.leave();
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.error("[tutorial] could not put everything back", e);
        }
        GtnhPlanner.LOG.info("[tutorial] stopped");
    }

    /** Where the tour is, for the dev harness. */
    public static String where() {
        final Tutorial t = current;
        if (t == null) return "off";
        final codechicken.nei.SearchField f = codechicken.nei.LayoutManager.searchField;
        final String search = f == null ? "none"
            : "[" + f.text() + "]" + (f.focused() ? " focused" : "") + " at " + f.x + "," + f.y + " " + f.w + "x" + f.h;
        return "step " + (t.director.beat + 1)
            + " of "
            + t.director.beats.size()
            + (t.director.catchingUp() ? " catching up" : t.director.waiting() ? " waiting" : " playing")
            + ": "
            + t.director.noteText()
            + " | search "
            + search
            + " | screen "
            + (Minecraft.getMinecraft().currentScreen == null ? "none"
                : Minecraft.getMinecraft().currentScreen.getClass()
                    .getSimpleName());
    }

    /**
     * Writes the start of every beat played this time into the mod's resources (the dev harness), for starting at any
     * beat at once. Returns the file, or null when the tour is not running.
     */
    public static java.io.File exportStarts() throws java.io.IOException {
        final Tutorial t = current;
        if (t == null) return null;
        final java.io.File repo = Minecraft.getMinecraft().mcDataDir.getAbsoluteFile()
            .getParentFile()
            .getParentFile();
        final java.io.File file = new java.io.File(repo, "src/main/resources/assets/gtnhplanner/tutorial/starts.json");
        t.director.exportStarts(file);
        return file;
    }

    /** Next, as the player presses it (the dev harness). */
    public static void nextBeat() {
        if (current != null && !current.director.next()) stop();
    }

    // region Events

    public static void register() {
        final Events events = new Events();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
    }

    public static final class Events {

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onRenderTick(final TickEvent.RenderTickEvent e) {
            final Tutorial t = current;
            if (t == null || e.phase != TickEvent.Phase.START) return;
            if (Minecraft.getMinecraft().theWorld == null) {
                stop();
                return;
            }
            try {
                t.director.frame();
            } catch (final RuntimeException | LinkageError ex) {
                GtnhPlanner.LOG.error("[tutorial] stopped by an error", ex);
                stop();
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onDrawn(final GuiScreenEvent.DrawScreenEvent.Post e) {
            final Tutorial t = current;
            if (t == null) return;
            t.director.drawn();
            t.draw();
        }

        /** The player's mouse works the callout only; a click elsewhere hurries the beat to its callout. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onMouse(final MouseInputEvent.Pre e) {
            final Tutorial t = current;
            if (t == null) return;
            e.setCanceled(true);
            if (!Mouse.getEventButtonState() || Mouse.getEventButton() != 0) return;
            final Minecraft mc = Minecraft.getMinecraft();
            final ScaledResolution sr = Targets.resolution();
            final float x = Mouse.getEventX() * sr.getScaledWidth() / (float) mc.displayWidth;
            final float y = sr.getScaledHeight() - Mouse.getEventY() * sr.getScaledHeight() / (float) mc.displayHeight
                - 1;
            if (!t.callout.click(x, y) && !t.director.waiting()) t.director.next();
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onKey(final KeyboardInputEvent.Pre e) {
            final Tutorial t = current;
            if (t == null) return;
            final int key = Keyboard.getEventKey();
            // The function keys (screenshots, the debug screen) stay the game's.
            if (key >= Keyboard.KEY_F1 && key <= Keyboard.KEY_F12 || key == Keyboard.KEY_F13) return;
            e.setCanceled(true);
            if (!Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) return;
            t.key(key);
        }

        /** The tour keeps a screen up; a world closing ends it. */
        @SubscribeEvent
        public void onOpen(final GuiOpenEvent e) {
            if (current == null) return;
            if (e.gui instanceof GuiMainMenu || Minecraft.getMinecraft().theWorld == null) {
                stop();
                return;
            }
            if (e.gui == null) e.gui = new TourScreen();
        }
    }

    // endregion

    private void key(final int key) {
        switch (key) {
            case Keyboard.KEY_ESCAPE -> stop();
            case Keyboard.KEY_RIGHT, Keyboard.KEY_SPACE, Keyboard.KEY_RETURN -> {
                if (!director.next()) stop();
            }
            case Keyboard.KEY_LEFT -> director.back();
            default -> {}
        }
    }

    private void draw() {
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = Targets.resolution();
        final int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        final long now = System.currentTimeMillis();
        final float dt = lastDraw < 0 ? 0 : Math.min(100, now - lastDraw);
        lastDraw = now;
        final float mx = Mouse.getX() * sw / (float) mc.displayWidth;
        final float my = sh - Mouse.getY() * sh / (float) mc.displayHeight - 1;
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 450);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        try {
            director.spotlight.draw(sw, sh, dt);
            callout.draw(sw, sh, mx, my);
            director.ghost.draw(director.px(), director.py(), dt);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("[tutorial] drawing failed", e);
        } finally {
            GL11.glPopMatrix();
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glColor4f(1, 1, 1, 1);
        }
    }
}
