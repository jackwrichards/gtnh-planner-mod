package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Builds the open plan again, a card at a time, for filming (the trailer's time-lapse): {@code call 'replay?ms=400'}
 * takes everything off the board, then puts the cards back one by one (by default from the right, the products, back
 * to the raw materials, as a player builds from NEI), each with the wires and drawers that now have both ends, through
 * the board's own edits, so every step draws, solves and saves as a player's would. The view eases after the build:
 * onto everything so far ({@code frame=grow}), or onto the last few cards ({@code frame=recent&recent=6}). Run it on a
 * throwaway copy of a plan (the harness's {@code importff}): it is the plan, rebuilt.
 */
public final class DevReplay {

    static final DevReplay INSTANCE = new DevReplay();

    private List<Node> order;
    private List<Edge> edges;
    private List<Drawer> drawers;
    private List<Note> notes;
    private List<Group> groups;
    /** Each drawer's links as the plan had them; the board's copy only links what has arrived. */
    private Map<UUID, List<Drawer.Link>> links;
    private final Set<UUID> present = new HashSet<>(), placedDrawers = new HashSet<>(), placedEdges = new HashSet<>(),
        placedGroups = new HashSet<>();
    private final List<UUID> arrived = new ArrayList<>();
    private int next;
    private long stepNanos, nextAt;
    private boolean frameGrow, framePending, running;
    private int recent;

    private DevReplay() {}

    Map<String, Object> start(final long ms, final boolean fromRight, final boolean grow, final int recentCount) {
        final BoardScreen b = DevBoard.screen();
        if (b == null) throw new IllegalStateException("open a plan on the board first");
        final BoardSession s = b.session();
        final Graph g = s.graph();
        edges = new ArrayList<>(g.edges.values());
        order = buildOrder(new ArrayList<>(g.nodes.values()), edges, fromRight);
        drawers = new ArrayList<>(g.drawers.values());
        notes = new ArrayList<>(g.notes.values());
        groups = new ArrayList<>(g.groups.values());
        links = new LinkedHashMap<>();
        for (final Drawer d : drawers) links.put(d.getId(), new ArrayList<>(d.getLinks()));
        present.clear();
        placedDrawers.clear();
        placedEdges.clear();
        placedGroups.clear();
        arrived.clear();
        s.edit(() -> {
            for (final Group gr : groups) g.removeGroup(gr.getId());
            for (final Drawer d : drawers) g.removeDrawer(d.getId());
            for (final Note n : notes) g.notes.remove(n.getId());
            for (final Node n : order) g.removeNode(n.id);
        });
        stepNanos = Math.max(16, ms) * 1_000_000L;
        frameGrow = grow;
        recent = Math.max(1, recentCount);
        next = 0;
        nextAt = System.nanoTime() + 600_000_000L;
        running = true;
        return Map.of("cards", order.size(), "wires", edges.size(), "drawers", drawers.size(), "stepMs", ms);
    }

    /**
     * The order a player would build in: the card furthest right (or left) first, then always the nearest card wired to
     * one already built (looking a recipe up from a port), and a new start when nothing built is wired to what is left.
     */
    private static List<Node> buildOrder(final List<Node> nodes, final List<Edge> edges, final boolean fromRight) {
        final Comparator<Node> byX = Comparator.comparingInt(n -> n.x);
        final Comparator<Node> start = (fromRight ? byX.reversed() : byX).thenComparingInt(n -> n.y);
        final Map<UUID, Set<UUID>> wired = new LinkedHashMap<>();
        for (final Edge e : edges) {
            wired.computeIfAbsent(e.sourceNodeId, k -> new HashSet<>())
                .add(e.targetNodeId);
            wired.computeIfAbsent(e.targetNodeId, k -> new HashSet<>())
                .add(e.sourceNodeId);
        }
        final List<Node> left = new ArrayList<>(nodes), out = new ArrayList<>();
        final Set<UUID> built = new HashSet<>();
        Node last = null;
        while (!left.isEmpty()) {
            Node pick = null;
            double best = Double.MAX_VALUE;
            for (final Node n : left) {
                if (last == null) break;
                boolean touches = false;
                for (final UUID other : wired.getOrDefault(n.id, Set.of())) if (built.contains(other)) touches = true;
                if (!touches) continue;
                final double d = Math.hypot(n.x - last.x, n.y - last.y);
                if (d < best) {
                    best = d;
                    pick = n;
                }
            }
            if (pick == null) pick = left.stream()
                .min(start)
                .orElseThrow();
            left.remove(pick);
            built.add(pick.id);
            out.add(pick);
            last = pick;
        }
        return out;
    }

    Map<String, Object> stop() {
        running = false;
        return Map.of("placed", next, "of", order == null ? 0 : order.size());
    }

    Map<String, Object> status() {
        return Map.of("running", running, "placed", next, "of", order == null ? 0 : order.size());
    }

    @SubscribeEvent
    public void onTick(final TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running) return;
        final BoardScreen b = DevBoard.screen();
        if (b == null) return;
        // The cards an edit adds are built a frame later; frame them then.
        if (framePending) {
            framePending = false;
            // Growing: the view holds still until the newest card would land off it, then takes in everything built.
            if (!frameGrow || !inView(
                b,
                arrived.isEmpty() ? null
                    : b.session()
                        .graph().nodes.get(lastNode))) {
                final List<UUID> ids = frameGrow ? new ArrayList<>(arrived)
                    : new ArrayList<>(arrived.subList(Math.max(0, arrived.size() - recent), arrived.size()));
                b.canvas()
                    .frame(ids);
            }
        }
        final long now = System.nanoTime();
        if (now < nextAt) return;
        nextAt = now + stepNanos;
        final BoardSession s = b.session();
        final Graph g = s.graph();
        if (next >= order.size()) {
            // Last, the notes and whatever never got an end on the board; then the whole plan.
            s.edit(() -> {
                for (final Note n : notes) g.notes.put(n.getId(), n);
                for (final Drawer d : drawers) if (placedDrawers.add(d.getId())) g.addDrawer(d);
            });
            arrived.addAll(placedDrawers);
            frameGrow = true;
            framePending = true;
            running = false;
            return;
        }
        final Node node = order.get(next++);
        s.edit(() -> {
            g.addNode(node);
            present.add(node.id);
            for (final Edge e : edges)
                if (present.contains(e.sourceNodeId) && present.contains(e.targetNodeId) && placedEdges.add(e.id))
                    g.addEdge(e);
            for (final Drawer d : drawers) {
                final List<Drawer.Link> all = links.get(d.getId());
                if (all.stream()
                    .noneMatch(
                        l -> l.nodeId()
                            .equals(node.id)))
                    continue;
                if (placedDrawers.add(d.getId())) {
                    for (final Drawer.Link l : all) if (!present.contains(l.nodeId())) d.removeLink(l);
                    g.addDrawer(d);
                    arrived.add(d.getId());
                } else for (final Drawer.Link l : all) if (l.nodeId()
                    .equals(node.id)) g.linkDrawer(d.getId(), l);
            }
            for (final Group gr : groups)
                if (!placedGroups.contains(gr.getId()) && present.containsAll(gr.getNodeIds())) {
                    placedGroups.add(gr.getId());
                    g.groups.put(gr.getId(), gr);
                }
        });
        arrived.add(node.id);
        lastNode = node.id;
        framePending = true;
    }

    private UUID lastNode;

    /** Whether a card sits wholly in the board's view, with a margin; false for none. */
    private static boolean inView(final BoardScreen b, final Node n) {
        if (n == null) return false;
        final Graph g = b.session()
            .graph();
        final float z = g.getZoom(), m = 24;
        final int w = b.canvas()
            .getArea().width,
            h = b.canvas()
                .getArea().height;
        final float x0 = g.getPanX() + n.x * z, y0 = g.getPanY() + n.y * z;
        final float x1 = x0 + com.gtnhplanner.ui.card.CardLayout.W * z, y1 = y0 + 160 * z;
        return x0 >= m && y0 >= m && x1 <= w - m && y1 <= h - m;
    }
}
