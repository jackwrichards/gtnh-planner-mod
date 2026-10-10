package com.gtnhplanner.ui.world;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The plan in a corner of the screen while playing: the last one open in the planner, drawn from its
 * {@link PlanSnapshot} as the board draws it at the map's zoom: zoomed out, cards with their machine and count, drawers
 * with their resource and rate, and with "Names when zoomed out" the same names placed the same way; past the board's
 * glance zoom, whole cards and drawers; the wires as the board routed them. Its size, shape, corner and zoom are
 * settings; the arrow keys pan it, and it highlights (and can follow) the
 * placed card or the plan's wire under the crosshair over the world.
 */
public final class Minimap {

    public static final Minimap INSTANCE = new Minimap();

    /** The world point the map is centred on, and whether the player moved it themselves since it last re-centred. */
    private float centreX, centreY;
    private boolean placed;
    /** The card to ring and glide to (the linked machine under the crosshair), or null. */
    @Nullable
    private UUID focus;
    /** The card (or else the wire) the crosshair is on over the world, set every frame by the overlay. */
    @Nullable
    private UUID pointed;
    @Nullable
    private PlanSnapshot.Line wire;

    /** Set by the dev harness for filming: the map in the middle of the screen. Never set in play. */
    public static volatile boolean devCentred;

    private Minimap() {}

    /** Back to where the board's view was. */
    public void recentre() {
        placed = false;
    }

    /** One tick of panning with the keys, in steps of the map's own size. */
    public void pan(final int dx, final int dy) {
        if (!placed) startAtBoardView();
        final float step = PlannerSettings.minimapSize() / 16f / zoom();
        centreX += dx * step;
        centreY += dy * step;
        placed = true;
    }

    /** The card of the linked machine under the crosshair, which the map rings and (when set) centres on. */
    public void focus(@Nullable final UUID cardId) {
        focus = cardId;
    }

    /**
     * The card or the plan's wire the crosshair is on over the world, which the map highlights and (when set) centres
     * on; the card goes before the placed spot under the crosshair ({@link #focus}).
     */
    public void point(@Nullable final UUID card, @Nullable final PlanSnapshot.Line line) {
        pointed = card;
        wire = line;
    }

    private void startAtBoardView() {
        centreX = PlanSnapshot.lastViewX();
        centreY = PlanSnapshot.lastViewY();
        placed = true;
    }

    private static float zoom() {
        return PlannerSettings.MINIMAP_ZOOMS[PlannerSettings.minimapZoomIndex()];
    }

    /** Last of the overlays, so the AR lens's cards never cover it. */
    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !PlannerSettings.minimap()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        // The tour's see-through screen shows the world, so the map shows over it too.
        if (mc.currentScreen != null && !(mc.currentScreen instanceof com.gtnhplanner.ui.tutorial.TourScreen)
            || mc.gameSettings.hideGUI
            || mc.gameSettings.showDebugInfo) return;
        draw(mc, event.resolution);
    }

    /** Where the map is on the screen, {x, y, w, h} with its frame and title; null when it is off. */
    @Nullable
    public static int[] bounds(final ScaledResolution sr) {
        if (!PlannerSettings.minimap()) return null;
        final int size = PlannerSettings.minimapSize(), margin = 6;
        int x0 = switch (PlannerSettings.minimapCorner()) {
            case TOP_LEFT, BOTTOM_LEFT -> margin;
            default -> sr.getScaledWidth() - margin - size;
        };
        int y0 = switch (PlannerSettings.minimapCorner()) {
            case TOP_LEFT, TOP_RIGHT -> margin;
            default -> sr.getScaledHeight() - margin - size - 40;
        };
        if (devCentred) {
            // Filming only (the dev harness's minimap?centre=1): in the middle of the screen, whatever the corner.
            x0 = (sr.getScaledWidth() - size) / 2;
            y0 = (sr.getScaledHeight() - size) / 2;
        }
        return new int[] { x0 - 2, y0 - 2, size + 4, size + 4 + (PlannerSettings.minimapCircle() ? 24 : 0) };
    }

    private void draw(final Minecraft mc, final ScaledResolution sr) {
        final int size = PlannerSettings.minimapSize();
        final boolean circle = PlannerSettings.minimapCircle();
        final int[] at = bounds(sr);
        final int x0 = at[0] + 2, y0 = at[1] + 2;
        final PlanSnapshot snap = PlanSnapshot.latest();
        GL11.glPushMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        frame(x0, y0, size, circle);
        if (snap == null) {
            Hyb.textCentered("Open the planner once", x0 + size / 2f, y0 + size / 2f - 9, Hyb.MUTED);
            Hyb.textCentered("to see your plan here", x0 + size / 2f, y0 + size / 2f + 1, Hyb.MUTED);
            GL11.glPopMatrix();
            return;
        }
        // Centre: the board's view, or where the player panned it, gliding to the card under the crosshair.
        if (!placed) startAtBoardView();
        final UUID which = pointed != null ? pointed : focus;
        final PlanSnapshot.Card focused = which == null ? null : snap.cardOf(which);
        final PlanSnapshot.Line lit = wire != null && snap.wires()
            .contains(wire) ? wire : null;
        final float[] to = lit != null ? middle(lit)
            : focused != null ? new float[] { focused.x() + focused.w() / 2, focused.y() + focused.h() / 2 } : null;
        if (to != null && PlannerSettings.minimapFollows()) {
            centreX += (to[0] - centreX) * 0.2f;
            centreY += (to[1] - centreY) * 0.2f;
        }
        final float z = zoom();
        final float ox = x0 + size / 2f - centreX * z, oy = y0 + size / 2f - centreY * z;
        clipBegin(sr, x0, y0, size, circle);
        LABELS.clear();
        drawWires(snap.wires(), ox, oy, z, lit);
        if (z > com.gtnhplanner.ui.card.RecipeCard.GLANCE_ZOOM) {
            // Close in, as the board: whole cards and drawers.
            for (final PlanSnapshot.Box b : snap.drawers()) whole(
                ox + b.x() * z,
                oy + b.y() * z,
                z,
                () -> { com.gtnhplanner.ui.card.PlanCardView.drawer(b, snap.rateUnit(), false); });
            for (final PlanSnapshot.Card c : snap.cards()) whole(
                ox + c.x() * z,
                oy + c.y() * z,
                z,
                () -> { com.gtnhplanner.ui.card.CleanCardView.draw(c, snap.rateUnit(), c == focused); });
        } else {
            drawDrawers(snap.drawers(), ox, oy, z, snap.rateUnit());
            drawCards(snap.cards(), ox, oy, z, focused);
        }
        if (z <= com.gtnhplanner.ui.card.RecipeCard.GLANCE_ZOOM && PlannerSettings.zoomedOutNames()) {
            GL11.glPushMatrix();
            GL11.glTranslatef(ox, oy, 0);
            GL11.glScalef(z, z, 1);
            LABELS.draw(z);
            GL11.glPopMatrix();
        }
        clipEnd(circle);
        // The plan's name, and whether it has changed since the planner last showed it.
        final Graph open = Plan.loaded() == null ? null : Plan.getActiveGraph();
        final boolean stale = open != snap.graph() || open.version() != snap.graphVersion();
        final String title = Hyb.fit(snap.planName(), size - 8);
        final float ty = circle ? y0 + size + 2 : y0 + 3;
        final float tx = circle ? x0 + (size - Hyb.width(title)) / 2f : x0 + 4;
        Hyb.rect(tx - 2, ty - 1, Hyb.width(title) + 4, 10, 0xB0000000);
        Hyb.text(title, tx, ty, Hyb.INK);
        if (stale) {
            final String note = "Changed: open the planner to update";
            final float nx = x0 + (size - Hyb.width(note)) / 2f, ny = circle ? ty + 10 : y0 + size - 11;
            Hyb.rect(nx - 2, ny - 1, Hyb.width(note) + 4, 10, 0xB0000000);
            Hyb.text(note, nx, ny, Hyb.AMBER_INK);
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    /** The map's frame and backing: a square, or a disc drawn in rows. */
    /** The map's backing, a little see-through; the keys strip under it is the same. */
    static final int BACKING = 0xB0141414;
    /** Its frame's edge and the dark line inside it, as see-through. */
    private static final int EDGE = 0xB03C3E45, INSIDE = 0xB01D1F23;

    private static void frame(final int x, final int y, final int size, final boolean circle) {
        Hyb.beginBatch();
        if (!circle) {
            // Rings and fill side by side, never over each other, so the see-through parts stay even.
            ring(x - 2, y - 2, size + 4, EDGE);
            ring(x - 1, y - 1, size + 2, INSIDE);
            Hyb.rect(x, y, size, size, BACKING);
            Hyb.endBatch();
            return;
        }
        final float r = size / 2f, cx = x + r;
        for (int row = -2; row < size + 2; row++) {
            final float dy = row + 0.5f - r;
            final float outer = (float) Math.sqrt(Math.max(0, (r + 2) * (r + 2) - dy * dy));
            final float inner = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            if (inner <= 0) {
                if (outer > 0) Hyb.rect(cx - outer, y + row, 2 * outer, 1, EDGE);
                continue;
            }
            Hyb.rect(cx - outer, y + row, outer - inner, 1, EDGE);
            Hyb.rect(cx + inner, y + row, outer - inner, 1, EDGE);
            Hyb.rect(cx - inner, y + row, 2 * inner, 1, BACKING);
        }
        Hyb.endBatch();
    }

    /** A one-pixel square ring. */
    private static void ring(final int x, final int y, final int size, final int argb) {
        Hyb.rect(x, y, size, 1, argb);
        Hyb.rect(x, y + size - 1, size, 1, argb);
        Hyb.rect(x, y + 1, 1, size - 2, argb);
        Hyb.rect(x + size - 1, y + 1, 1, size - 2, argb);
    }

    /**
     * Clips what follows to the map: the scissor for a square; for a circle the stencil buffer when the game has one
     * (nothing else here touches it), else the depth buffer, written close to the eye outside the circle. Items turn
     * the
     * depth test off as they draw, so with the depth mask the map also leaves out icons centred outside the circle.
     */
    private static void clipBegin(final ScaledResolution sr, final int x, final int y, final int size,
        final boolean circle) {
        final Minecraft mc = Minecraft.getMinecraft();
        final int f = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, mc.displayHeight - (y + size) * f, size * f, size * f);
        roundX = x + size / 2f;
        roundY = y + size / 2f;
        roundR = circle ? size / 2f : Float.MAX_VALUE;
        if (!circle) return;
        final float r = size / 2f, cx = x + r;
        stencil = GL11.glGetInteger(GL11.GL_STENCIL_BITS) > 0;
        if (stencil) {
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
            GL11.glColorMask(false, false, false, false);
            Hyb.beginBatch();
            for (int row = 0; row < size; row++) {
                final float dy = row + 0.5f - r;
                final float half = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
                if (half > 0) Hyb.rect(cx - half, y + row, 2 * half, 1, 0xFF000000);
            }
            Hyb.endBatch();
            GL11.glColorMask(true, true, true, true);
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            return;
        }
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDepthMask(true);
        GL11.glColorMask(false, false, false, false);
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 500);
        Hyb.beginBatch();
        for (int row = 0; row < size; row++) {
            final float dy = row + 0.5f - r;
            final float half = (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            Hyb.rect(x, y + row, cx - half - x, 1, 0xFF000000);
            Hyb.rect(cx + half, y + row, x + size - cx - half, 1, 0xFF000000);
        }
        Hyb.endBatch();
        GL11.glPopMatrix();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
        masked = true;
    }

    /** The round map's centre and radius (no radius for a square), and whether it clips with the stencil. */
    private static float roundX, roundY, roundR;
    private static boolean stencil;

    /** Whether an icon centred here shows: inside the map, for a round one its circle. */
    private static boolean inMap(final float cx, final float cy) {
        return Math.hypot(cx - roundX, cy - roundY) <= roundR;
    }

    /** Whether the round mask is up: drawing an item turns the depth test off, and the mask needs it back on. */
    private static boolean masked;

    private static void keepMask() {
        if (!masked) return;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(false);
    }

    private static void clipEnd(final boolean circle) {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        if (stencil) GL11.glDisable(GL11.GL_STENCIL_TEST);
        if (masked) {
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        masked = false;
        stencil = false;
        roundR = Float.MAX_VALUE;
    }

    /** The wires as the board routed them; {@code lit} on top, in the highlight with a soft halo. */
    private static void drawWires(final List<PlanSnapshot.Line> lines, final float ox, final float oy, final float z,
        @Nullable final PlanSnapshot.Line lit) {
        Hyb.beginBatch();
        for (final PlanSnapshot.Line l : lines) {
            if (l == lit) continue;
            final float w = Math.max(1, l.width() * z * 0.8f);
            path(l, ox, oy, z, w, l.flowing() ? l.color() : l.color() & 0x00FFFFFF | 0x80000000);
        }
        if (lit != null) {
            final float w = Math.max(1.5f, lit.width() * z * 0.8f);
            path(lit, ox, oy, z, w + 4, 0x50000000 | Hyb.LIT & 0xFFFFFF);
            path(lit, ox, oy, z, w, Hyb.mix(0xFF000000 | lit.color(), Hyb.LIT, 0.6f));
        }
        Hyb.endBatch();
    }

    private static void path(final PlanSnapshot.Line l, final float ox, final float oy, final float z, final float w,
        final int color) {
        final List<int[]> p = l.path();
        for (int i = 1; i < p.size(); i++) segment(
            ox + p.get(i - 1)[0] * z,
            oy + p.get(i - 1)[1] * z,
            ox + p.get(i)[0] * z,
            oy + p.get(i)[1] * z,
            w,
            color);
    }

    /** Halfway along a wire's route, on the board. */
    private static float[] middle(final PlanSnapshot.Line l) {
        final List<int[]> p = l.path();
        double total = 0;
        for (int i = 1; i < p.size(); i++)
            total += Math.hypot(p.get(i)[0] - p.get(i - 1)[0], p.get(i)[1] - p.get(i - 1)[1]);
        double left = total / 2;
        for (int i = 1; i < p.size(); i++) {
            final int[] a = p.get(i - 1), b = p.get(i);
            final double run = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (run >= left && run > 0) {
                final double t = left / run;
                return new float[] { (float) (a[0] + (b[0] - a[0]) * t), (float) (a[1] + (b[1] - a[1]) * t) };
            }
            left -= run;
        }
        final int[] end = p.get(p.size() - 1);
        return new float[] { end[0], end[1] };
    }

    /** A run of wire {@code w} wide: a rectangle when straight, two triangles when diagonal. */
    private static void segment(final float ax, final float ay, final float bx, final float by, final float w,
        final int color) {
        final float h = w / 2;
        if (ay == by) Hyb.rect(Math.min(ax, bx) - h, ay - h, Math.abs(bx - ax) + w, w, color);
        else if (ax == bx) Hyb.rect(ax - h, Math.min(ay, by) - h, w, Math.abs(by - ay) + w, color);
        else {
            final float len = (float) Math.hypot(bx - ax, by - ay), vx = -(by - ay) / len * h, vy = (bx - ax) / len * h;
            Hyb.triangle(ax + vx, ay + vy, bx + vx, by + vy, bx - vx, by - vy, color);
            Hyb.triangle(ax + vx, ay + vy, bx - vx, by - vy, ax - vx, ay - vy, color);
        }
    }

    /** Draws a whole card or drawer at a place on the map, at the map's zoom, keeping the round map's mask. */
    private static void whole(final float x, final float y, final float z, final Runnable draw) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(z, z, 1);
        draw.run();
        GL11.glPopMatrix();
        keepMask();
    }

    /** The names over cards and drawers, placed as the board places them zoomed out. */
    private static final com.gtnhplanner.ui.canvas.ZoomedOutLabels LABELS = new com.gtnhplanner.ui.canvas.ZoomedOutLabels();

    /** Drawers as the board draws them zoomed out: the resource big on its tinted tile, the rate in a dark pill. */
    private static void drawDrawers(final List<PlanSnapshot.Box> boxes, final float ox, final float oy, final float z,
        final Fmt.RateUnit unit) {
        for (final PlanSnapshot.Box b : boxes) {
            final float x = ox + b.x() * z, y = oy + b.y() * z, w = b.w() * z, h = b.h() * z;
            Hyb.beginBatch();
            com.gtnhplanner.ui.drawer.DrawerPaint
                .shape(b.kind(), x, y, w, h, 0, com.gtnhplanner.ui.drawer.DrawerPaint.edge(b.kind()), z);
            com.gtnhplanner.ui.drawer.DrawerPaint
                .shape(b.kind(), x, y, w, h, -1, com.gtnhplanner.ui.drawer.DrawerPaint.fill(b.kind()), z);
            Hyb.endBatch();
            final float side = Math.min(h - 4, w * 0.5f);
            if (side >= 6 && inMap(x + 2 + side / 2f, y + h / 2f)) {
                if (b.power()) com.gtnhplanner.ui.card.RecipeCard.euIcon(x + 2, y + (h - side) / 2f, side);
                else Hyb.icon(b.item(), b.fluid(), x + 2, y + (h - side) / 2f, side, 0);
                keepMask();
            }
            final String sign = b.rate() <= 0 ? "" : b.source() ? "-" : "+";
            final int color = b.rate() <= 0 ? Hyb.MUTED
                : com.gtnhplanner.ui.drawer.DrawerPaint.rateInk(b.kind(), b.rate());
            pill(sign + Fmt.brief(b.power() ? b.rate() / 20 : b.rate() * unit.perSecond), x, y, w, h, color);
            LABELS.drawer(b.label(), b.x(), b.y(), b.w(), b.h(), b.source(), b.rate());
        }
    }

    /** Cards as the board draws them zoomed out: the machine big on its tinted tile, the count in a dark pill. */
    private static void drawCards(final List<PlanSnapshot.Card> cards, final float ox, final float oy, final float z,
        @Nullable final PlanSnapshot.Card focused) {
        for (final PlanSnapshot.Card c : cards) {
            final float x = ox + c.x() * z, y = oy + c.y() * z, w = c.w() * z, h = c.h() * z;
            final int tint = c.tint() < 0 ? 0x8A93A6 : c.tint();
            Hyb.rect(x, y, w, h, Hyb.mix(tint, 0x262B34, 0.55f));
            Hyb.rect(x + 1, y + 1, w - 2, h - 2, Hyb.mix(tint, 0x07090C, 0.26f));
            final float side = Math.min(w, h) - 6;
            if (side >= 6 && inMap(x + w / 2f, y + h / 2f)) {
                if (c.art() != null) {
                    final float s = Math.min(
                        side / c.art()
                            .width(),
                        side / c.art()
                            .height());
                    final float pw = c.art()
                        .width() * s,
                        ph = c.art()
                            .height() * s;
                    Hyb.texture(
                        c.art()
                            .location(),
                        x + (w - pw) / 2f,
                        y + (h - ph) / 2f,
                        pw,
                        ph);
                } else if (c.machine() != null) {
                    final float s = Math.min(side, 32);
                    Hyb.item(c.machine(), x + (w - s) / 2f, y + (h - s) / 2f, s, 0);
                    keepMask();
                }
            }
            pill(
                "×" + Fmt.machines(c.machines()),
                x,
                y,
                w,
                h,
                c.pinned() ? Hyb.GOLD : c.machines() <= 0 ? Hyb.MUTED : Hyb.INK);
            if (c == focused) Hyb.ring(x - 1, y - 1, w + 2, h + 2, 1, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
            LABELS.card(c.name(), c.x(), c.y(), c.w(), c.h());
        }
    }

    /** A figure in a dark pill in a tile's corner, a GUI pixel per font pixel; left out when the tile is too small. */
    private static void pill(final String text, final float x, final float y, final float w, final float h,
        final int color) {
        final float tw = Hyb.width(text);
        if (tw + 4 > w - 2 || h < 14) return;
        final float px = x + w - 2 - tw - 2, py = y + h - 2 - 10;
        Hyb.rect(px, py, tw + 2, 10, 0xC0101114);
        Hyb.text(text, px + 1, py + 1, color);
    }
}
