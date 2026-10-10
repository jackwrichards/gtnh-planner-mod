# Dev harness: driving the client from a shell

The dev harness lets an agent (or a script) run the GTNH Planner client without anyone at the keyboard: launch it,
wait until it is in a world, open the flowchart, click/drag/scroll/type, read the widget tree, take screenshots,
and shut it down. It exists so UI work can be verified the same way logic is verified by `./gradlew test`.

It is dev-only: `DevHarness.initIfDev()` does nothing outside a deobfuscated dev environment (force with
`-Dgtnhplanner.dev=true|false`), so it never runs in a shipped jar.

## Quick start

```bash
tools/dev/mc.sh start            # build + launch runClient25, block until the test world is loaded (~20s warm)
tools/dev/mc.sh call open        # open the GTNH Planner flowchart
tools/dev/mc.sh shot look.png    # -> run/client/screenshots/look.png
tools/dev/mc.sh part 1 TIER      # click card 1's tier chip, found by name via /board (also: move, scroll 'amount=-1', 'button=1');
                                 # d0 RATE = drawer 0's rate box; ports are IN0, OUT1... (drag them with /drag)
tools/dev/mc.sh stop             # kills the game (a clean quit can hang on a confirm dialog)
tools/dev/mc.sh smoke            # all of the above as a pass/fail check
```

After changing mod code, `tools/dev/mc.sh swap --reopen` pushes it into the running game (about 12s, see
[Hot swap](#hot-swap)); `tools/dev/mc.sh restart` rebuilds and relaunches (about 30s) for what swapping can't do.

`mc.sh start` writes these into `run/client/options.txt` before launch (env var overrides in brackets):
window 1920x1080 (`PLANNH_DEV_WIDTH`/`PLANNH_DEV_HEIGHT`), GUI scale 2 (`PLANNH_DEV_GUI_SCALE`), master volume 0
(`PLANNH_DEV_SOUND`; `call 'sound?volume=0..1'` changes it while running). The harness turns off
pause-on-lost-focus so an unfocused window keeps running.

`PLANNH_GTNH=1` adds `-PgtnhRecipes`: the GTNH core mod, GregTech and the pack's recipes (72 mods instead of 29;
about 30s to the world once the jars are cached). It uses its own world, `plannh-dev-gtnh`, because opening a world
across mod sets stops on EndlessIDs' "convert this world?" prompt, which the harness cannot get past.

On first launch it creates a creative superflat world `plannh-dev` (`-Dgtnhplanner.dev.world=name`, empty to stay on
the main menu) and loads it automatically. Plans are saved per world, so notes and nodes persist across runs;
with the game stopped, delete `run/client/saves/plannh-dev` (the world) and
`run/client/saves/NEI/local/plannh-dev` (the plan) for a clean slate.

## Endpoints

HTTP on `127.0.0.1:25599` (`-Dgtnhplanner.dev.port`), GET with query parameters, JSON replies. `mc.sh call 'path?args'`
is a thin curl wrapper. Every request waits until the game has processed it, so calls can be chained.

| Endpoint | What it does |
| --- | --- |
| `/status` | `ready`, `inWorld`, `screen`, `muiScreen`, `hovered` and `focused` widgets, `windowActive` (the game window has focus), `idleSeconds` (since the owner last moved the mouse or pressed something in it; the harness's own input does not count, and `mc.sh idle` gates on it), `fps`, display size, `guiWidth`/`guiHeight`/`guiScale`, mouse position |
| `/open`, `/close` | open the flowchart (same as F8) / close the current screen |
| `/screenshot?name=x.png[&x&y&w&h]` | save the next fully drawn frame, optionally cropped to a GUI-coordinate rectangle; returns the path |
| `/widgets` | ModularUI widget tree of the current screen: type, name, x/y/w/h in GUI coordinates, children; open popups (menus, number boxes) under `popups` |
| `/move?x&y` | move the mouse (hover) |
| `/click?x&y[&button=0][&count=1][&mods=shift]` | button 0 left, 1 right, 2 middle; `count=2` double-clicks; `mods` as for `/key` |
| `/drag?x1&y1&x2&y2[&steps=10][&button=0][&mods=shift][&hold=1]` | press, move in steps (one per tick, and never two in one frame), release; `mods` held throughout (Shift-drag box-selects); `hold=1` keeps the button (and `mods`) down at the end, for a screenshot mid-drag |
| `/release[?button=0]` | lets go of a button held by `drag?...&hold=1` |
| `/scroll?x&y[&amount=1]` | wheel; positive is up |
| `/key?code[&char][&mods=ctrl,shift][&hold=ms]` | press + release an LWJGL2 key code (modifiers held around it; `hold` keeps it down that long) (`Keyboard.KEY_*`: 1 Esc, 28 Enter, 14 Backspace, 20 T, 66 F8) |
| `/type?text` | type text into the focused field |
| `/cmd?c=/time%20set%20day` | run a command as the player (client commands first, then chat) |
| `/window?w&h&scale` | sets the game window to w by h screen pixels (the full pack's Prism instance opens it at 854x480 whatever its settings say) and the GUI scale (0 Auto; the open screen is laid out again, options.txt untouched; GTNH ships 4); neither: only reads them, with the GUI size |
| `/sfx[?play=name&pitch]` | the planner's sounds played lately, newest first, with volume and pitch (the dev game is muted, so a test checks what played here); `play` plays one by its enum or sounds.json name |
| `/look?yaw&pitch` | turn the player (yaw 0 south, 90 west, 180 north, 270 east; pitch down positive); returns the position and view (neither given: only reads them, to put the player back after a test) |
| `/machine?x&y&z&feed=minecraft:iron_ore&count=16` | GT runs: fill a single-block machine's energy and put the stack in its input, so it runs a real recipe (for the AR lens) |
| `/machine?own=1` | GT runs: give every ownerless GregTech machine near the player an owner (machines placed with `/setblock ... {mID:1000}` have none, and GregTech crashes when an ownerless multiblock is broken) |
| `/board` | the open board as data: zoom/pan, per card its state (tier, amps, coil, machines, pinned, ports, `worldLinks`: where it is placed) and the GUI rect of each control and port (`parts.TIER`, `parts.OUT1`, with `cx/cy`), per drawer its kind, rule, target, rate, unmet flag and parts, the edge count, every routed wire (`wires`: resource, then its points), `solving`, and the notices |
| `/cards?from&count&scale&match` | card designs side by side for the plan last open: today's card and the trial (`ui/card/CleanCardView`), on the board's canvas (`dev/CardGallery`); `match` keeps cards whose machine's name has it; Esc closes it |
| `/view?zoom&panX&panY` | set the board view (defaults 1, 0, 0) so tests start from a known place |
| `/slots[?add=name \| switch=i \| delete=i]` | the plan slots (`slots`, `active`); `add` opens a new one, so a check can work without touching the owner's plans |
| `/exportff?file=path` | writes the open plan as the website's project JSON, as posting it would send (a relative path is from the repo root); returns the file |
| `/importff?file=path \| text=...` | imports a Factory Flow plan (JSON, plan code or link; a relative path is from the repo root) as a new slot and makes it active; returns `name`, `summary` and the `report` lines |
| `/gtmachines?q&all=1&art=1` | GregTech multiblocks (all=1: every machine) by meta and in-game name; art=1 adds the bundled picture each resolves to |
| `/recipeinfo?output&handler&input` | what NEI and GTNH Planner see in a recipe (ingredient, result and other stacks with registry names, catalysts, the ports a card would get), read-only |
| `/nei?item&uses=1&planner=0&tab` | open NEI's recipes (or uses) for an item: over the planner, as R and U do there, or with `planner=0` from the inventory, the planner closed; `tab` opens on the first tab whose name contains it; returns the tab names |
| `/library?url=<base>` | the site the Library reads and posts to; `node tools/dev/mock-library.mjs [port]` runs a local stand-in (accounts and posts in memory, posted plans written to `build/dev-client/mock-posts/`; posts can be edited and deleted as on the site, and icons go without the website's picture), so sign-in, posting, editing and deleting never touch gtnhplanner.com; the URL resets on restart |
| `/structurepic?meta=1000` | GT runs only: (re)build that multiblock controller's recipe-card picture (structure built in BlockRenderer6343's fake world, replaces the cached one so an open board shows it), save it as `screenshots/structure-<meta>.png`; returns `status` (ok, too big, empty, not a multiblock, failed: ...), `size`, `blocks`, timings and `path`. Without `meta`: every constructable controller as `{meta, name}` |
| `/record?start=name[&fps=24&width=1280&threads=3&quality=0.92&cursor=0]`, `/record?stop=1` | demo footage: JPEG frames from the back buffer to `recordings/<name>/` (other windows never get in the way); `width` scales them down, `cursor=0` leaves out the drawn mouse arrow (the tour draws its own); frames are dropped, never queued, when writing falls behind, and `stop` reports how many |
| `/film` | filming: the tour's cursor builds a polyethylene line from nothing (`ui/tutorial/Film`: NEI, then every machine looked up from its card's input port and added with the plan button, wires, drawers, a rate, Arrange), in the tour's sandbox; follow and stop it as the tour (`tutorial`, `tutorial?stop=1`) |
| `/rebuild?file=<plan>[&seed=1]` | filming: any finished plan (a library download, a plan code or link; a relative path is from the repo root) built again by the tour's cursor as a player would (`ui/tutorial/Rebuild`): the first recipe from NEI's list onto a new plan, every other card looked up from a built card's port and added with its plan button (landing wired), the plan's other wires dragged, its drawers dragged out with their kinds and rates, Arrange and Fit, each card taking the plan's settings as it lands; it rests, zooms out and pans about like a person, the same way for the same `seed`. A beat per card and stage, each waiting for next (`tutorial?next=1`) |
| `/replay?ms=400[&order=left][&frame=recent&recent=6]`, `/replay?stop=1`, `/replay` | filming a time-lapse: takes the open plan off the board and builds it again a card at a time (products first, or `order=left` raw materials first), each with the wires and drawers that now have both ends, through the board's own edits; the view eases onto everything so far (or the last few cards). It rebuilds the plan itself, so run it on a throwaway import |
| `/planpicture[?detail=simple][&name=1][&flows=1]` | the Share key's Screenshot... saved straight to `screenshots/` (detailed cards unless `detail=simple`; `name`/`flows` add the footer); returns the path and size |
| `/minimap?centre=1[&on=1&size=2&zoom=6&circle=0]` | filming: the minimap drawn in the middle of the screen whatever its corner (`centre=0` puts it back; never saved); `on`, `size`, `zoom` (steps, as the gear's rows) and `circle` set its settings (saved, as the gear does) |
| `/newworld?name=plannh-trailer[&seed=N]` | filming somewhere real: a creative world of normal terrain (made when new); call it twice, a few seconds apart, from a world (the first leaves it). Plans are per world |
| `/seethrough[?percent=0..100]` | the gear's See-through (0 a solid board, as for recording; it is saved like the slider's); without `percent`, only reads it |
| `/frame` | run one ModularUI frame update and report what is hovered and below the mouse (hover debugging) |
| `/tutorial[?start=1 \| step=N \| next=1 \| stop=1 \| export=1 \| neilist=1]` | the tour: start it, start or jump to a step (from its shipped or saved start, else hurrying from the nearest one before), press next (hurry the step to its note, or go on), stop it (everything put back), or write every played step's start to `starts.json`; `neilist=1` lists NEI's list as it stands; always returns where the tour is, with NEI's search and the open screen. It takes over the game, so gate it on `mc.sh idle` like any input |
| `/quit` | ask the game to quit (with GT this can stop on a "really close?" dialog; `mc.sh stop` kills instead) |

On Git Bash, set `MSYS_NO_PATHCONV=1` before passing a command that starts with `/` (it is otherwise rewritten
into a Windows path). 1.7.10's `/tp` takes no facing; use `/look`.

All coordinates are GUI-scaled (what `GuiScreen` sees as `mouseX`/`mouseY`), not window pixels. Find targets with
`/widgets` and confirm with `/status` (`hovered`) instead of estimating from screenshots.

## Tips for UI work

- A GTNH Planner text field that is not focused by the click that opens it takes focus on a double-click:
  `click?x&y&count=2`, check `focused` in `/status`, then `type`. Esc (`key?code=1`) ends the edit.
- The board zooms with the wheel around the cursor; left-drag pans on empty board and moves a card or drawer (with
  the rest of the selection); Shift-drag box-selects. The top bar's `?` lists every gesture.
- `tools/dev/board-check.sh` (GT runs) is the end-to-end check of the board: a real EBF recipe and its defaults, a
  port drag into a drawer, a rate solved, auto-wiring, undo/redo, no solver errors. It works in a plan slot of its
  own and deletes it afterwards, and refuses to run while the game window has focus (`FORCE=1` overrides).
- Cropped screenshots are cheap to inspect and keep native resolution:
  `mc.sh shot detail.png 'x=30&y=50&w=150&h=90'`.
- `mc.sh smoke` fails on a crash, on any exception with a `com.gtnhplanner` frame, or if the flowchart does not
  open, and lists other logged errors (other mods, ModularUI layout warnings) without failing.
- Logs: `build/dev-client/client.log` is the full output of the current run (all mods; rewritten on each start).

## Hot swap

`mc.sh start` runs the game on JetBrains Runtime 25 with HotswapAgent and a JDWP port on `127.0.0.1:5005`
(`PLANNH_HOTSWAP=0` turns this off, `PLANNH_JDWP_PORT` moves it). Then:

```bash
tools/dev/mc.sh swap            # recompile (~10s) and redefine every class whose bytes changed (~1s)
tools/dev/mc.sh swap --reopen   # same, then close and reopen the flowchart so widget-building code reruns
```

Works for method bodies and for structural changes to loaded classes (adding methods was verified; JBR's
enhanced redefinition also allows fields and signature changes). Code that draws every frame updates at once;
code that builds widgets needs `--reopen`.

Needs `mc.sh restart` instead:
- new classes, or changed classes the game hasn't loaded yet: swap lists them and exits 2;
- mixins, resources (lang files, textures), and anything that only runs at startup (static initializers, event and
  keybind registration, config loading).

How it works: the game loads GTNH Planner from the dev jar (`shadowJar` then `downgradeJar`), which is locked while the
game runs, but its GTNH Planner classes are byte-identical to `build/tmp/downgradeMainClasses/main` (checked 435 of 435),
where the game uses the `META-INF/versions/21` copies on Java 25. `start` builds both in one Gradle run and, once the
client is ready, records each class's SHA-1 in `build/dev-client/hotswap.sums`; `swap` rebuilds that folder and
sends only classes whose hash differs, through JDI `redefineClasses` (`tools/dev/Hotswap.java`). Hashes, not
timestamps: the Mixin and Lombok annotation processors force full recompiles, and redefining all ~260 classes in one
batch crashed JBR (`EXCEPTION_ACCESS_VIOLATION` in `VM_EnhancedRedefineClasses::do_topological_class_sorting`).

## How it works

- **Input** goes into lwjgl3ify's emulated LWJGL2 queues (`org.lwjglx.input.Mouse.addMoveEvent` / `addButtonEvent` /
  `addWheelEvent`, `Keyboard.addRawKeyEvent`), the same entry points its SDL event loop uses for real input, so the
  game cannot tell the difference and the user's real cursor is never touched. Typing mirrors
  `Lwjgl3ifyEventLoop#handleTextEvent`: an `InputEvents` text event (vanilla text fields) plus one char event per
  character (`GuiScreen#keyTyped`, ModularUI). `Mouse.isButtonDown` reads `sdlMouseButtonFlags`, which the harness
  keeps in step for drags. All of this is reflection because lwjgl3ify and LWJGL's SDL bindings are only on the
  Java 17+ run classpath, so synthetic input needs `runClient25` (or 17/21), not the Java 8 `runClient`.
- **Timing**: input steps run one per client tick (at `ClientTickEvent` START, before the game polls input);
  screenshots run at the end of a render tick after skipping one frame, so earlier requests are visible.
- **Screenshots** use `ScreenShotHelper` on the main framebuffer, which includes all GUI layers.
- **Process control**: killing the Gradle run task does not stop the forked game, so the harness writes its PID to
  `run/client/plannh-dev.pid` and `mc.sh stop` kills that process (only if it is still a java process). It never
  asks the game to quit: with GT loaded that can stop on a "really close?" dialog. Plans save on every edit.

## Troubleshooting

- `a client is already answering on port 25599`: run `tools/dev/mc.sh stop`.
- A game window that no script controls (e.g. started from an IDE): close it, or `taskkill //PID <pid> //F`.
- The project must live at a short path on Windows: Gradle and Minecraft dev hit MAX_PATH under deep folders.
