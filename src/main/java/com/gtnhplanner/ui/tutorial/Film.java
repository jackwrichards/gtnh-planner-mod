package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.PlanMenu;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.tutorial.Targets.Target;
import com.gtnhplanner.ui.tutorial.Tour.Beat;

import codechicken.nei.recipe.GuiCraftingRecipe;

/**
 * Not the tour: a build for the trailer's time-lapse, played by the tour's cursor the way the tour plays (dev harness
 * {@code call film}). A polyethylene line from nothing, each recipe looked up the way a player would: polyethylene
 * from NEI's list onto a new plan, then every machine before it from its card's input port (NEI's page, the recipe's
 * plan button shift-clicked, the card landing beside its port, wired), the inputs and outputs dragged out to drawers
 * (one oxygen drawer wired to both reactors that want it), a rate asked of the product, Arrange and Fit. A beat per
 * stage, each waiting at its end for next, so the harness can say where it is.
 */
final class Film {

    private Film() {}

    static List<Beat> beats() {
        final List<Beat> out = new ArrayList<>();
        opening(beat(out));
        lookUp(
            beat(out),
            PE,
            "Ethylene",
            ETHYLENE,
            () -> onPage("chemical", List.of("ethanol", "sulfuric acid"), "ethylene"));
        lookUp(
            beat(out),
            ETHYLENE,
            "Sulfuric",
            SULFURIC,
            () -> onPage("chemical", List.of("sulfur trioxide", "water"), "sulfuric acid"));
        lookUp(
            beat(out),
            SULFURIC,
            "Trioxide",
            TRIOXIDE,
            () -> onPage("chemical", List.of("sulfur dioxide", "oxygen"), "sulfur trioxide"));
        lookUp(
            beat(out),
            TRIOXIDE,
            "Dioxide",
            DIOXIDE,
            () -> onPage("chemical", List.of("sulfur", "oxygen"), "sulfur dioxide"));
        // What comes in and goes out, each to a drawer beside its port; one oxygen drawer feeds both reactors.
        final Beat drawers = beat(out);
        // Cards sit 80 apart in a row, so drawers go above or below their ports, where the board is open; the oxygen
        // drawer above the gap between the two reactors that take it, both in reach.
        drawer(drawers, DIOXIDE, false, "Oxygen", 370, -170);
        drawers.move(Targets.port(TRIOXIDE, false, "Oxygen"))
            .pause(120)
            .drag(Targets.port(TRIOXIDE, false, "Oxygen"), Targets.drawer("Oxygen", DrawerCard.Part.BODY))
            .pause(250);
        drawer(drawers, DIOXIDE, false, "Sulfur", 0, -170);
        drawer(drawers, SULFURIC, false, "Water", 0, -170);
        drawer(drawers, ETHYLENE, false, "Ethanol", 0, -170);
        drawer(drawers, ETHYLENE, true, "Diluted", 0, 190);
        drawer(drawers, PE, false, "Air", 0, -170);
        drawer(drawers, PE, true, "Polyethylene", 90, 0);
        // A rate on the product: the whole line works itself out.
        beat(out).opens(Targets.drawer("Polyethylene", DrawerCard.Part.RATE))
            .type(Steps::focusedField, "144")
            .pause(150)
            .commit()
            .until(Film::solved, 5000)
            .pause(600)
            .rest();
        beat(out).click(Targets.topKey("arrange"))
            .until(
                () -> Targets.board() != null && !Targets.board()
                    .canvas()
                    .arranging(),
                20000)
            .pause(300)
            .click(Targets.topKey("fit"))
            .pause(1200)
            .rest();
        return out;
    }

    private static Beat beat(final List<Beat> out) {
        final Beat b = new Beat();
        out.add(b);
        return b;
    }

    // region The cards

    /** A card (a recipe's) one of whose outputs is called exactly {@code name}. */
    private static Predicate<Node> making(final String name) {
        return n -> n.powerSource == null && n.outputs.stream()
            .anyMatch(
                p -> p.getDisplayName()
                    .equalsIgnoreCase(name));
    }

    private static final Predicate<Node> PE = n -> n.powerSource == null && n.outputs.stream()
        .anyMatch(
            p -> p.getDisplayName()
                .toLowerCase(Locale.ROOT)
                .contains("polyethylene"));
    private static final Predicate<Node> ETHYLENE = making("Ethylene"), SULFURIC = making("Sulfuric Acid"),
        TRIOXIDE = making("Sulfur Trioxide"), DIOXIDE = making("Sulfur Dioxide");

    private static boolean has(final Predicate<Node> which) {
        return Targets.card(which) != null;
    }

    private static boolean hasDrawer(final String label) {
        final BoardScreen b = Targets.board();
        if (b == null) return false;
        for (final DrawerModel d : b.session()
            .drawerModels()
            .values())
            if (d.label.toLowerCase(Locale.ROOT)
                .contains(label.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean solved() {
        final BoardScreen b = Targets.board();
        return b != null && !b.session()
            .solving();
    }

    // endregion

    // region NEI

    /**
     * The first recipe on NEI's open page, in a tab named {@code tab}, taking each of {@code in} and making
     * {@code out}.
     */
    private static TourRecipes.Found onPage(final String tab, final List<String> in, final String out) {
        return TourRecipes.onPage(tab, in, out);
    }

    private static Target planButton(final Supplier<TourRecipes.Found> recipe) {
        return () -> {
            final TourRecipes.Found f = recipe.get();
            return f == null ? null
                : Targets.planButton(f.handler(), f.recipe())
                    .rect();
        };
    }

    /** NEI's stack for polyethylene as a fluid (GregTech's molten plastic), to look its recipes up. */
    private static ItemStack polyethylene() {
        try {
            final net.minecraftforge.fluids.Fluid f = net.minecraftforge.fluids.FluidRegistry
                .getFluid("molten.plastic");
            return f == null ? null : gregtech.api.util.GTUtility.getFluidDisplayStack(f);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("[film] no polyethylene", e);
            return null;
        }
    }

    /** From the inventory: polyethylene searched in NEI, its recipe, the plan button, a new plan on the reactor. */
    private static void opening(final Beat b) {
        final Supplier<TourRecipes.Found> pe = () -> onPage("chemical", List.of("ethylene"), "polyethylene");
        final Target peButton = planButton(pe);
        final Target lcrRow = Targets.planMenuRow("Large Chemical");
        b.pause(300)
            .click(Targets.neiSearch())
            .type(Steps::neiSearch, "polyethylene")
            .pause(250)
            .hover(
                Targets.neiItem(
                    s -> TourRecipes.name(s)
                        .contains("polyethylene")),
                250)
            .key("R", () -> {
                final ItemStack s = polyethylene();
                if (s != null) GuiCraftingRecipe.openRecipeGui("item", s);
            })
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(250)
            .run(() -> TourRecipes.open(pe.get()))
            .until(() -> peButton.rect() != null, 3000)
            .pause(200)
            .click(peButton)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(Targets.planMenuRow("New plan"), 200)
            .click(Targets.planMenuRow("New plan"))
            .until(() -> lcrRow.rect() != null || Targets.board() != null, 2000)
            .when(() -> lcrRow.rect() != null, Steps.seq(Steps.hover(lcrRow, 200), Steps.click(lcrRow)))
            .until(() -> has(PE), 5000)
            .pause(500);
    }

    /**
     * What makes one of a card's inputs, as a player finds it: a click on the input (NEI's page of what makes it), the
     * recipe shown, its plan button shift-clicked (no menus: this plan, this tab's machine), and the new card lands
     * beside the port, wired to it.
     */
    private static void lookUp(final Beat b, final Predicate<Node> card, final String input,
        final Predicate<Node> makes, final Supplier<TourRecipes.Found> recipe) {
        final Target button = planButton(recipe);
        b.click(Targets.port(card, false, input))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(250)
            .run(() -> TourRecipes.open(recipe.get()))
            .until(() -> button.rect() != null, 3000)
            .pause(200)
            .shiftClick(button)
            .until(() -> has(makes), 5000)
            .pause(400);
    }

    /** A port dragged out to a drawer of its own, on open board nearest {@code dx, dy} board units from the port. */
    private static void drawer(final Beat b, final Predicate<Node> card, final boolean output, final String port,
        final float dx, final float dy) {
        final Target p = Targets.port(card, output, port);
        // To the port first: one off the view pans it in, and only then is the drop beside it worked out.
        b.move(p)
            .pause(120)
            .drag(p, Targets.freeNear(p, output, dx, dy))
            .until(() -> hasDrawer(port), 2500)
            .pause(200);
    }

    // endregion
}
