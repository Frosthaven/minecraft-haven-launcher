package dev.thedragon.havenlauncher;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Haven instance's pre-launch step (Prism runs it before every start):
 * {@code "$INST_JAVA" -jar haven-launcher.jar <pack.toml URL>}.
 * <ol>
 * <li>Runs the newest launcher the pack delivered instead, when it differs from this one ({@link SelfUpdate}).</li>
 * <li>Opens the launcher window ({@link LauncherWindow}); without a screen everything prints to the console.</li>
 * <li>On the first launch only, offers to import the player's files from another install ({@link OldInstalls},
 * {@link ImportDialog}); the footer button offers it on any launch.</li>
 * <li>Moves the instance to the pack's NeoForge without a question, from the next launch on ({@link LoaderSync}).</li>
 * <li>Runs packwiz-installer-bootstrap (next to this jar) without its window and shows its progress
 * ({@link Updater}); puts optional files the installer met for the first time back to the pack default, asks on
 * the first launch, and runs it again when that changed anything ({@link OptionalMods}).</li>
 * <li>After a good update, adds or removes the offline editor's bridge mods ({@link OfflineEditor}).</li>
 * <li>Exits 0 on Play now or when the countdown ends; closing the window or a failed update exits 1 and stops the
 * launch, as before. Without a window the installer's exit code is ours.</li>
 * </ol>
 * The import, the NeoForge step and the editor step can never block the update: any error there is printed and skipped.
 */
public final class HavenLauncher {
    private final Path game;
    private final String packUrl;
    private final LauncherWindow window;   // null: no screen, console only
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "haven-launcher");
        t.setDaemon(true);
        return t;
    });
    private boolean newLoader;

    private HavenLauncher(Path game, String packUrl, LauncherWindow window) {
        this.game = game;
        this.packUrl = packUrl;
        this.window = window;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals(LoaderSync.KEEP)) {   // the background helper LoaderSync starts
            LoaderSync.keep(Path.of(args[1]), args[2], args[3]);
            return;
        }
        if (args.length != 1) {
            System.err.println("usage: haven-launcher.jar <pack.toml URL>");
            System.exit(2);
        }
        System.setProperty("apple.awt.application.name", "Haven");
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        Path game = gameDir();
        try {
            Integer delegated = SelfUpdate.delegate(game, args);
            if (delegated != null) {
                System.exit(delegated);
            }
        } catch (Exception e) {
            System.err.println("Haven: launcher self-update skipped: " + e);
        }
        LauncherWindow window = null;
        if (!GraphicsEnvironment.isHeadless() && !"off".equals(System.getProperty("haven.ui"))) {
            try {
                window = LauncherWindow.open(game);
            } catch (Throwable t) {
                System.err.println("Haven: no window (" + t + "); console mode");
            }
        }
        System.exit(new HavenLauncher(game, args[0], window).run());
    }

    int run() throws Exception {
        if (window == null) {
            return session();
        }
        window.onImport(() -> worker.submit(this::importFromFooter));
        window.onOptional(() -> worker.submit(this::optionalFromFooter));
        worker.submit(this::session);
        return window.await();
    }

    /** The launch: import prompt, NeoForge, then the update. Returns the exit code for the console flow. */
    private int session() {
        try {
            firstRunImport();
        } catch (Throwable t) {
            System.err.println("Haven: import skipped: " + t);
        }
        try {
            newLoader = LoaderSync.sync(instanceDir(game), packUrl);
        } catch (Throwable t) {
            System.err.println("Haven: NeoForge check skipped: " + t);
        }
        return update();
    }

    /** The update and what follows it; also what "Try again" and a changed optional-mod choice run. */
    private int update() {
        Path bootstrap = game.resolve(Updater.BOOTSTRAP);
        if (!Files.exists(bootstrap)) {
            String why = bootstrap + " is missing; re-import the Haven instance";
            System.err.println("Haven: " + why);
            if (window != null) {
                window.failed("The update could not start", why, null);
            }
            return 1;
        }
        String release = Updater.release(packUrl), installed = Updater.installed(game);
        if (window != null) {
            window.busy("Updating Haven");
            window.release(release == null ? "" : installed != null && !installed.equals(release)
                    ? "Updating to Haven " + release + " (from " + installed + ")" : "Haven " + release);
        }
        try {
            boolean first = !Files.exists(game.resolve(OptionalMods.MANIFEST));
            Set<String> before = OptionalMods.read(game).keySet();
            Updater.Result r = Updater.run(game, packUrl, progress());
            if (r.code() == 0) {
                Set<String> changed = new HashSet<>(OptionalMods.applyDefaults(game, Updater.base(packUrl), before));
                if (first && window != null) {
                    changed.addAll(chooseOptional());
                }
                if (!changed.isEmpty()) {
                    System.out.println("Haven: optional mods changed (" + String.join(", ", changed) + "); applying");
                    if (window != null) {
                        window.busy("Applying your optional mods");
                    }
                    r = Updater.run(game, packUrl, progress());
                }
            }
            if (r.code() == 0) {
                if (!newLoader) {   // a NeoForge switch session keeps the bridge it has (it matches the old one)
                    try {
                        OfflineEditor.sync(game);
                    } catch (Throwable t) {
                        System.err.println("Haven: offline editor step skipped: " + t);
                    }
                }
                if (release != null) {
                    Updater.remember(game, release);
                    if (window != null) {
                        window.release("Haven " + release);
                    }
                }
                if (window != null) {
                    window.ready("Up to date", "Haven is ready.");
                }
            } else if (window != null) {
                window.failed("The update did not finish", r.last(), () -> worker.submit(this::update));
            }
            return r.code();
        } catch (Exception e) {
            System.err.println("Haven: update failed: " + e);
            if (window != null) {
                window.failed("The update did not finish", e.toString(), () -> worker.submit(this::update));
            }
            return 1;
        }
    }

    private Updater.Progress progress() {
        return window != null ? window : new Updater.Progress() {
            @Override
            public void phase(String text) {
            }

            @Override
            public void file(int done, int total, String text) {
            }
        };
    }

    // ---- optional mods ---------------------------------------------------------------------------

    /** The Optional mods window; returns the paths whose choice changed (written to packwiz.json). */
    private Set<String> chooseOptional() throws Exception {
        List<OptionalMods.Entry> entries = OptionalMods.entries(game, Updater.base(packUrl));
        if (entries.isEmpty()) {
            return Set.of();
        }
        Map<String, Boolean> values = window.dialog(() -> OptionalMods.dialog(window.frame(), entries));
        return values == null ? Set.of() : OptionalMods.write(game, values);
    }

    private void optionalFromFooter() {
        try {
            if (!chooseOptional().isEmpty()) {
                update();
            }
        } catch (Exception e) {
            System.err.println("Haven: optional mods: " + e);
            window.say("Optional mods could not be changed: " + e.getMessage());
        }
    }

    // ---- import ----------------------------------------------------------------------------------

    private void firstRunImport() throws Exception {
        Path done = game.resolve(OldInstalls.DONE);
        if (Files.exists(done)) {
            return;
        }
        List<OldInstalls.Install> found = OldInstalls.find(game);
        if (found.isEmpty()) {
            Files.writeString(done, "no old install found\n");
            System.out.println("Haven: no other install to import from");
            return;
        }
        if ("first".equals(System.getProperty("haven.import.auto"))) {
            OldInstalls.Install pick = found.get(0);
            imported(pick, OldInstalls.copy(pick, game, OldInstalls.defaults(pick, OldInstalls.presence(pick))));
            return;
        }
        if (window == null) {
            System.out.println("Haven: no screen to ask on; import skipped for now");
            return;
        }
        ImportDialog.Choice choice = window.dialog(() -> ImportDialog.show(window.frame(), found, game));
        if (choice == null) {
            return;   // "Not now": asks again next launch
        }
        if (choice.install() == null) {
            Files.writeString(done, "declined\n");
            return;
        }
        imported(choice.install(), OldInstalls.copy(choice.install(), game, choice.items()));
    }

    private void importFromFooter() {
        try {
            List<OldInstalls.Install> found = OldInstalls.find(game);   // may be empty: the dialog still offers Browse…
            ImportDialog.Choice choice = window.dialog(() -> ImportDialog.show(window.frame(), found, game));
            if (choice == null || choice.install() == null) {
                if (choice != null) {
                    Files.writeString(game.resolve(OldInstalls.DONE), "declined\n");
                }
                return;
            }
            imported(choice.install(), OldInstalls.copy(choice.install(), game, choice.items()));
        } catch (Exception e) {
            System.err.println("Haven: import failed: " + e);
            window.say("Import failed: " + e.getMessage());
        }
    }

    private void imported(OldInstalls.Install from, int files) throws IOException {
        Files.writeString(game.resolve(OldInstalls.DONE), "imported " + files + " files from " + from.game() + "\n");
        System.out.println("Haven: imported " + files + " files from " + from.game());
        if (window != null) {
            window.say("Imported " + files + " files from " + from + ".");
        }
    }

    // ---- folders ---------------------------------------------------------------------------------

    /** Prism's instance folder (holds mmc-pack.json): $INST_DIR, else the game folder's parent. */
    static Path instanceDir(Path game) {
        String dir = System.getenv("INST_DIR");
        return dir != null && !dir.isBlank() ? Path.of(dir).toAbsolutePath().normalize() : game.getParent();
    }

    /** Prism's instance game folder: $INST_MC_DIR, else the working folder Prism starts pre-launch commands in. */
    static Path gameDir() {
        String dir = System.getenv("INST_MC_DIR");
        return (dir != null && !dir.isBlank() ? Path.of(dir) : Path.of("")).toAbsolutePath().normalize();
    }
}
