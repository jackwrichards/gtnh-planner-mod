package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.PlanMenu;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.tutorial.Targets.Target;
import com.gtnhplanner.ui.tutorial.Tour.Beat;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;

/**
 * Not the tour: a finished plan built again by the tour's cursor, for the trailer's time-lapses (dev harness
 * {@code call 'rebuild?file=<plan>&seed=1'}). It builds as a player would: the first recipe found in NEI's list onto a
 * new plan, then every other card from a port of one already built (a click on an input for what makes it, a right
 * click on an output for what uses it), the plan's own recipe turned to on NEI's page and its plan button
 * shift-clicked, so the card lands beside the port, wired; the plan's other wires dragged by hand, its drawers dragged
 * out of their ports, their kinds switched and rates typed, then Arrange and Fit. Each card takes the plan's settings
 * as it lands (its machine, tier, pins), unseen, so the numbers come out as the plan's. Between cards it acts like a
 * person (seeded, so a take can be made again): it rests on things to read their tips, stops to zoom out and pan
 * about, and takes its time differently each time. A beat per card and per stage; each waits at its end for next.
 */
final class Rebuild {

    private Rebuild() {}

    /** The plan's cards and drawers already built, by the plan's ids: what they are on the board. */
    private static final Map<UUID, UUID> CARDS = new HashMap<>(), DRAWERS = new HashMap<>();

    private record Next(Node node, Edge via) {}

    static List<Beat> beats(final Graph plan, final long seed) {
        CARDS.clear();
        DRAWERS.clear();
        final Random rnd = new Random(seed);
        final List<Beat> out = new ArrayList<>();
        final List<Next> order = order(plan);
        final Set<UUID> before = new HashSet<>();
        for (int i = 0; i < order.size(); i++) {
            final Next n = order.get(i);
            final Beat b = beat(out);
            if (i == 0) first(b, n.node(), rnd);
            else if (n.node()
                .isPower()) power(b, n.node(), rnd);
            else if (n.via() == null) fromSearch(b, n.node(), rnd);
            else lookUp(b, n, rnd);
            before.add(n.node().id);
            wireRest(b, plan, n.node(), before, rnd);
            if (rnd.nextFloat() < 0.3f)
                b.hover(Targets.card(card(n.node().id), RecipeCard.Part.TIER), 900 + rnd.nextInt(700));
            if (i > 0 && i % (4 + rnd.nextInt(3)) == 0) lookAround(b, rnd);
        }
        // Then a look over all of it before the drawers.
        final Beat over = beat(out);
        over.click(Targets.topKey("fit"))
            .pause(1400 + rnd.nextInt(600));
        lookAround(over, rnd);
        final List<Drawer> drawers = new ArrayList<>(plan.getDrawers());
        drawers.sort(Comparator.comparingInt(d -> orderOf(order, d)));
        Beat db = beat(out);
        int count = 0;
        for (final Drawer d : drawers) {
            drawer(db, d, rnd);
            if (++count % 6 == 0) {
                if (rnd.nextFloat() < 0.5f) lookAround(db, rnd);
                db = beat(out);
            }
        }
        // The rates the plan asks for, typed; the rest of its rules as they were.
        final Beat rates = beat(out);
        for (final Drawer d : drawers) if (d.getKind()
            .hasRule() && d.getRate() > 0) rate(rates, d, rnd);
        rates.until(Rebuild::solved, 8000)
            .pause(1200);
        final Beat tidy = beat(out);
        tidy.click(Targets.topKey("arrange"))
            .until(
                () -> Targets.board() != null && !Targets.board()
                    .canvas()
                    .arranging(),
                30000)
            .pause(400)
            .click(Targets.topKey("fit"))
            .pause(1800);
        lookAround(tidy, rnd);
        tidy.click(Targets.topKey("fit"))
            .pause(1500)
            .rest();
        return out;
    }

    /**
     * A new beat, which first puts the board back if something left it (a drag let go too soon is a click, and a click
     * on a port opens NEI's page): NEI's page shut, the planner opened again.
     */
    private static Beat beat(final List<Beat> out) {
        final Beat b = new Beat();
        out.add(b);
        b.escIf(() -> Targets.recipePage() != null)
            .when(
                () -> Targets.board() == null && out.size() > 1,
                Steps.seq(Steps.run(() -> Director.openScene(Tour.Scene.BOARD)), Steps.pause(600)));
        return b;
    }

    // region The order: from the product back, each card from one already built

    /**
     * The card furthest right first (the product), then always the nearest card wired to one already built, by the wire
     * it is found from; a card wired to nothing built yet starts afresh from NEI's list.
     */
    private static List<Next> order(final Graph plan) {
        final List<Node> left = new ArrayList<>(plan.getNodes());
        final List<Next> out = new ArrayList<>();
        final Set<UUID> built = new HashSet<>();
        Node last = null;
        while (!left.isEmpty()) {
            Node pick = null;
            Edge via = null;
            double best = Double.MAX_VALUE;
            if (last != null) for (final Node n : left) {
                for (final Edge e : plan.getEdges()) {
                    final UUID other = e.sourceNodeId.equals(n.id) ? e.targetNodeId
                        : e.targetNodeId.equals(n.id) ? e.sourceNodeId : null;
                    if (other == null || !built.contains(other)) continue;
                    final Node o = plan.nodes.get(other);
                    final double d = Math.hypot(n.x - last.x, n.y - last.y) + (o == last ? 0 : 200);
                    if (d < best) {
                        best = d;
                        pick = n;
                        via = e;
                    }
                }
            }
            if (pick == null) {
                pick = left.stream()
                    .max(Comparator.comparingInt((Node n) -> n.x))
                    .orElseThrow();
                via = null;
            }
            left.remove(pick);
            built.add(pick.id);
            out.add(new Next(pick, via));
            last = pick;
        }
        return out;
    }

    private static int orderOf(final List<Next> order, final Drawer d) {
        int best = Integer.MAX_VALUE;
        for (final Drawer.Link l : d.getLinks()) for (int i = 0; i < order.size(); i++) if (order.get(i)
            .node().id.equals(l.nodeId())) best = Math.min(best, i);
        return best;
    }

    // endregion

    // region What is on the board

    private static java.util.function.Predicate<Node> card(final UUID planId) {
        return n -> n.id.equals(CARDS.get(planId));
    }

    private static BoardSession session() {
        final BoardScreen b = Targets.board();
        return b == null ? null : b.session();
    }

    private static boolean solved() {
        final BoardSession s = session();
        return s != null && !s.solving();
    }

    /** A port of a built card, by index: one off the view pans the board to it, and reads as not there yet. */
    private static Target port(final UUID planId, final boolean output, final int index) {
        return () -> {
            final BoardScreen b = Targets.board();
            final UUID id = CARDS.get(planId);
            if (b == null || id == null) return null;
            final BoardCanvas c = b.canvas();
            final RecipeCard card = c.cards()
                .get(id);
            if (card == null || card.layout() == null || card.model() == null) return null;
            final Node n = card.model().node;
            if (index >= (output ? n.outputs.size() : n.inputs.size())) return null;
            final int lx = CardLayout.iconX(output), ly = card.layout()
                .rowY(output, index) + CardLayout.ICON_Y;
            return Targets.worldRect(c, n.x + lx, n.y + ly, CardLayout.ICON, CardLayout.ICON);
        };
    }

    /** A part of a built drawer. */
    private static Target drawerPart(final UUID planDrawer, final DrawerCard.Part part) {
        return () -> {
            final BoardScreen b = Targets.board();
            final UUID id = DRAWERS.get(planDrawer);
            if (b == null || id == null) return null;
            final DrawerCard d = b.canvas()
                .drawers()
                .get(id);
            if (d == null || d.model() == null) return null;
            final int[] r = d.partRect(part);
            if (r == null) return null;
            final Drawer dr = d.model().drawer;
            return Targets.worldRect(b.canvas(), dr.getX() + r[0], dr.getY() + r[1], r[2], r[3]);
        };
    }

    // endregion

    // region Cards

    /** The cards on the board now, to tell the new one when it lands. */
    private static void snapshot(final Set<UUID> into) {
        into.clear();
        final BoardSession s = session();
        if (s != null) into.addAll(s.graph().nodes.keySet());
    }

    private static Node fresh(final Set<UUID> before) {
        final BoardSession s = session();
        if (s == null) return null;
        for (final Node n : s.graph()
            .getNodes()) if (!before.contains(n.id) && !CARDS.containsValue(n.id)) return n;
        return null;
    }

    /** The card that just landed is the plan's: its machine and settings as the plan has them. */
    private static void adopt(final Node plan, final Set<UUID> before) {
        final BoardSession s = session();
        final Node n = fresh(before);
        if (s == null || n == null) return;
        CARDS.put(plan.id, n.id);
        s.edit(() -> {
            n.machineConfig.copySettingsFrom(plan.machineConfig);
            n.machineConfig.setMachineCount(plan.machineConfig.getMachineCount());
            n.setMachineCountFixed(plan.isMachineCountFixed());
            n.targetOutputRates.clear();
            n.targetOutputRates.putAll(plan.targetOutputRates);
            if (!n.isPower()) n.machineName = plan.machineName;
            else {
                n.powerSettings.clear();
                n.powerSettings.putAll(plan.powerSettings);
                n.refresh();
            }
        });
    }

    /** NEI's open page, turned to the plan's recipe. */
    private static void openPlanRecipe(final Node plan) {
        final GuiRecipe<?> page = Targets.recipePage();
        if (page != null && plan.recipeId != null) page.openTargetRecipe(plan.recipeId);
    }

    /** The plan button of the plan's recipe on NEI's page, found once and kept. */
    private static Target button(final Node plan) {
        final Object[] found = new Object[2];
        return () -> {
            final GuiRecipe<?> page = Targets.recipePage();
            if (page == null) {
                found[0] = null;
                return null;
            }
            if (found[0] == null || !page.currenthandlers.contains(found[0])) {
                found[0] = null;
                outer: for (final IRecipeHandler h : page.currenthandlers)
                    for (int i = 0; i < h.numRecipes(); i++) if (Recipe.RecipeId.of(h, i)
                        .equals(plan.recipeId)) {
                            found[0] = h;
                            found[1] = i;
                            break outer;
                        }
                if (found[0] == null) return null;
            }
            return Targets.planButton((IRecipeHandler) found[0], (Integer) found[1])
                .rect();
        };
    }

    /** The NEI stack of a card's first output, to search for and look up. */
    private static ItemStack firstOutput(final Node plan) {
        for (final var p : plan.outputs) {
            final ItemStack s = p.getDisplayStack();
            if (s != null) return s;
        }
        return null;
    }

    private static String searchText(final ItemStack s) {
        final String n = TourRecipes.name(s);
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    /** From the inventory: the first card's output searched in NEI, R, its recipe, the plan button, a new plan. */
    private static void first(final Beat b, final Node plan, final Random rnd) {
        final ItemStack s = firstOutput(plan);
        final Target button = button(plan);
        final String machine = plan.machineName == null ? "" : plan.machineName;
        final Target machineRow = Targets.planMenuRow(machine);
        final Set<UUID> before = new HashSet<>();
        b.pause(400)
            .click(Targets.neiSearch())
            .type(Steps::neiSearch, s == null ? "" : searchText(s))
            .pause(300 + rnd.nextInt(300))
            .hover(
                Targets.neiItem(
                    x -> s != null && TourRecipes.name(x)
                        .equals(TourRecipes.name(s))),
                300 + rnd.nextInt(400))
            .key("R", () -> { if (s != null) GuiCraftingRecipe.openRecipeGui("item", s.copy()); })
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(300 + rnd.nextInt(400))
            .run(() -> openPlanRecipe(plan))
            .until(() -> button.rect() != null, 3000)
            .pause(300)
            .run(() -> snapshot(before))
            .click(button)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(Targets.planMenuRow("New plan"), 250)
            .click(Targets.planMenuRow("New plan"))
            .until(() -> machineRow.rect() != null || Targets.board() != null, 2000)
            .when(() -> machineRow.rect() != null, Steps.seq(Steps.hover(machineRow, 250), Steps.click(machineRow)))
            .when(() -> Targets.board() == null && PlanMenu.INSTANCE.isOpen(), Steps.click(Targets.planMenuRow("")))
            .until(() -> fresh(before) != null, 6000)
            .run(() -> adopt(plan, before))
            .pause(600 + rnd.nextInt(400));
    }

    /** A card wired to nothing built yet: searched in NEI's list from the board, R, its recipe, plus. */
    private static void fromSearch(final Beat b, final Node plan, final Random rnd) {
        final ItemStack s = firstOutput(plan);
        final Target button = button(plan);
        final Set<UUID> before = new HashSet<>();
        b.click(Targets.neiSearch())
            .type(Steps::neiSearch, s == null ? "" : searchText(s))
            .pause(300 + rnd.nextInt(300))
            .hover(
                Targets.neiItem(
                    x -> s != null && TourRecipes.name(x)
                        .equals(TourRecipes.name(s))),
                300 + rnd.nextInt(300))
            .key("R", () -> { if (s != null) Planner.lookUp(s.copy(), false); })
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(250 + rnd.nextInt(300))
            .run(() -> openPlanRecipe(plan))
            .until(() -> button.rect() != null, 3000)
            .pause(200)
            .run(() -> snapshot(before))
            .shiftClick(button)
            .until(() -> fresh(before) != null, 5000)
            .run(() -> adopt(plan, before))
            .escIf(() -> Targets.recipePage() != null)
            .pause(300 + rnd.nextInt(400));
    }

    /**
     * A card found from a port of one already built: an input clicked (what makes it) or an output right-clicked (what
     * uses it), the plan's recipe on NEI's page, its plan button shift-clicked; it lands beside the port, wired.
     */
    private static void lookUp(final Beat b, final Next next, final Random rnd) {
        final Node plan = next.node();
        final Edge e = next.via();
        final boolean makes = e.sourceNodeId.equals(plan.id);
        final UUID from = makes ? e.targetNodeId : e.sourceNodeId;
        final Target p = port(from, !makes, makes ? e.targetInputIndex : e.sourceOutputIndex);
        final Target button = button(plan);
        final Set<UUID> before = new HashSet<>();
        b.move(p);
        if (rnd.nextFloat() < 0.45f) b.hover(p, 500 + rnd.nextInt(800));
        b.run(() -> snapshot(before))
            .add(Steps.click(p, makes ? 0 : 1))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200 + rnd.nextInt(450))
            .run(() -> openPlanRecipe(plan))
            .until(() -> button.rect() != null, 3000)
            .pause(150 + rnd.nextInt(400))
            .shiftClick(button)
            .until(() -> fresh(before) != null, 5000)
            .run(() -> adopt(plan, before))
            .escIf(() -> Targets.recipePage() != null)
            .pause(250 + rnd.nextInt(500));
    }

    /** A generator: the non-recipe picker, its tile by name. */
    private static void power(final Beat b, final Node plan, final Random rnd) {
        final com.gtnhplanner.power.PowerSource src = com.gtnhplanner.power.PowerRegistry.get(plan.powerSource);
        final Target tile = Targets.boardPart("picker:" + (src == null ? plan.powerSource : src.name()));
        final Set<UUID> before = new HashSet<>();
        b.run(() -> snapshot(before))
            .click(Targets.topKey("nonrecipe"))
            .until(
                () -> Targets.boardPart("picker:sheet")
                    .rect() != null,
                2500)
            .pause(400 + rnd.nextInt(500))
            .hover(tile, 300 + rnd.nextInt(400))
            .click(tile)
            .until(() -> fresh(before) != null, 4000)
            .run(() -> adopt(plan, before))
            .pause(300 + rnd.nextInt(400));
    }

    // endregion

    // region Wires

    /** Whether the board already has the plan's wire between built cards. */
    private static boolean wired(final Edge e) {
        final BoardSession s = session();
        final UUID a = CARDS.get(e.sourceNodeId), b = CARDS.get(e.targetNodeId);
        if (s == null || a == null || b == null) return true;
        for (final Edge w : s.graph()
            .getEdges())
            if (w.sourceNodeId.equals(a) && w.targetNodeId.equals(b)
                && w.sourceOutputIndex == e.sourceOutputIndex
                && w.targetInputIndex == e.targetInputIndex) return true;
        return false;
    }

    /**
     * The plan's other wires between this card and ones built before it, dragged port to port with both in view; when
     * they cannot both be read at once, wired as a player would wire them far apart, from the menu, unseen.
     */
    private static void wireRest(final Beat b, final Graph plan, final Node node, final Set<UUID> built,
        final Random rnd) {
        for (final Edge e : plan.getEdges()) {
            final UUID other = e.sourceNodeId.equals(node.id) ? e.targetNodeId
                : e.targetNodeId.equals(node.id) ? e.sourceNodeId : null;
            if (other == null || !built.contains(other)) continue;
            final Target from = port(e.sourceNodeId, true, e.sourceOutputIndex),
                to = port(e.targetNodeId, false, e.targetInputIndex);
            b.when(
                () -> !wired(e) && readable(e),
                Steps.seq(
                    Steps.run(() -> frame(e)),
                    Steps.pause(450),
                    Steps.move(from),
                    Steps.drag(from, to),
                    Steps.pause(200 + rnd.nextInt(300))))
                .when(() -> !wired(e), Steps.run(() -> connect(e)));
        }
    }

    private static List<UUID> ends(final Edge e) {
        final List<UUID> ids = new ArrayList<>();
        if (CARDS.get(e.sourceNodeId) != null) ids.add(CARDS.get(e.sourceNodeId));
        if (CARDS.get(e.targetNodeId) != null) ids.add(CARDS.get(e.targetNodeId));
        return ids;
    }

    private static boolean readable(final Edge e) {
        final BoardScreen b = Targets.board();
        return b != null && ends(e).size() == 2
            && b.canvas()
                .framesReadably(ends(e));
    }

    private static void frame(final Edge e) {
        final BoardScreen b = Targets.board();
        if (b != null) b.canvas()
            .frame(ends(e));
    }

    private static void connect(final Edge e) {
        final BoardSession s = session();
        final UUID a = CARDS.get(e.sourceNodeId), b = CARDS.get(e.targetNodeId);
        if (s != null && a != null && b != null) s.connect(a, e.sourceOutputIndex, b, e.targetInputIndex);
    }

    // endregion

    // region Drawers

    /** The board's drawer at a built card's port, in a direction. */
    private static Drawer drawerAt(final Drawer.Link l, final boolean input) {
        final BoardSession s = session();
        final UUID id = CARDS.get(l.nodeId());
        return s == null || id == null ? null
            : s.graph()
                .drawerAt(id, l.portIndex(), input);
    }

    /**
     * One of the plan's drawers: dragged out of its first port onto open board (inputs above, outputs below), its other
     * ports dragged onto it, its kind switched to the plan's with its key.
     */
    private static void drawer(final Beat b, final Drawer d, final Random rnd) {
        final List<Drawer.Link> links = d.getLinks();
        if (links.isEmpty()) return;
        final boolean input = d.getKind()
            .linksInputs();
        final Drawer.Link first = links.get(0);
        final Target p = port(first.nodeId(), !input, first.portIndex());
        closeIn(b, () -> CARDS.get(first.nodeId()));
        b.when(
            () -> CARDS.get(first.nodeId()) != null && drawerAt(first, input) == null,
            Steps.seq(
                Steps.move(p),
                Steps.pause(100 + rnd.nextInt(200)),
                Steps.drag(
                    p,
                    Targets.freeNear(
                        p,
                        !input,
                        rnd.nextInt(60) - 30,
                        input ? -170 - rnd.nextInt(40) : 190 + rnd.nextInt(40))),
                Steps.until(() -> drawerAt(first, input) != null, 2500)))
            .escIf(() -> Targets.recipePage() != null)
            .run(() -> {
                final Drawer made = drawerAt(first, input);
                if (made != null) DRAWERS.put(d.getId(), made.getId());
            });
        for (final Drawer.Link l : links.subList(1, links.size())) {
            final Target q = port(l.nodeId(), !input, l.portIndex());
            b.when(
                () -> DRAWERS.get(d.getId()) != null && CARDS.get(l.nodeId()) != null && drawerAt(l, input) == null,
                Steps.seq(Steps.move(q), Steps.drag(q, drawerPart(d.getId(), DrawerCard.Part.BODY)), Steps.pause(200)))
                .escIf(() -> Targets.recipePage() != null);
        }
        // A product drawer switched to byproduct or trash, a press of its key for each.
        final int presses = d.getKind() == Drawer.Kind.BYPRODUCT ? 1 : d.getKind() == Drawer.Kind.TRASH ? 2 : 0;
        for (int i = 0; i < presses; i++) b.when(
            () -> DRAWERS.get(d.getId()) != null,
            Steps.seq(Steps.click(drawerPart(d.getId(), DrawerCard.Part.CYCLE)), Steps.pause(250)));
        b.pause(150 + rnd.nextInt(350));
    }

    /** A drawer's rate typed into its box, then its rule as the plan has it. */
    private static void rate(final Beat b, final Drawer d, final Random rnd) {
        final double r = d.getRate();
        final String text = r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.4g", r);
        closeIn(b, () -> DRAWERS.get(d.getId()));
        b.when(
            () -> DRAWERS.get(d.getId()) != null,
            Steps.seq(
                Steps.clickOpens(drawerPart(d.getId(), DrawerCard.Part.RATE), 0),
                Steps.type(Steps::focusedField, text),
                Steps.pause(150),
                Steps.enterCloses(),
                Steps.run(() -> {
                    final BoardSession s = session();
                    final UUID id = DRAWERS.get(d.getId());
                    final Drawer made = s == null || id == null ? null
                        : s.graph()
                            .getDrawer(id);
                    if (made != null) s.edit(() -> made.setTarget(d.getRule(), d.getRate()));
                }),
                Steps.pause(500 + rnd.nextInt(600))));
    }

    // endregion

    /**
     * Zoomed out past where cards show their ports (the glance view), the board eases onto a card or drawer first, as a
     * person zooms in on what they are about to work on.
     */
    private static void closeIn(final Beat b, final Supplier<UUID> id) {
        b.when(() -> {
            final BoardSession s = session();
            return s != null && id.get() != null
                && s.graph()
                    .getZoom() <= RecipeCard.GLANCE_ZOOM + 0.001f;
        }, Steps.seq(Steps.run(() -> {
            final BoardScreen b2 = Targets.board();
            if (b2 != null) b2.canvas()
                .frame(List.of(id.get()));
        }), Steps.pause(500)));
    }

    /** A person stopping to look: zoomed out a little, the board dragged about, back in. */
    private static void lookAround(final Beat b, final Random rnd) {
        b.pause(300 + rnd.nextInt(500))
            .wheel(Targets.canvasArea(), -1 - rnd.nextInt(2))
            .pause(700 + rnd.nextInt(700))
            .pan(Targets.emptyBoard(60, 60), (rnd.nextFloat() - 0.5f) * 260, (rnd.nextFloat() - 0.5f) * 160)
            .pause(500 + rnd.nextInt(700));
        if (rnd.nextBoolean())
            b.pan(Targets.emptyBoard(60, 60), (rnd.nextFloat() - 0.5f) * 220, (rnd.nextFloat() - 0.5f) * 120)
                .pause(400 + rnd.nextInt(500));
        b.wheel(Targets.canvasArea(), 1 + rnd.nextInt(2))
            .pause(300 + rnd.nextInt(400));
    }
}
