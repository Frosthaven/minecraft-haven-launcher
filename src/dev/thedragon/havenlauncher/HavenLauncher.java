package dev.thedragon.havenlauncher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Haven instance's pre-launch step (Prism runs it before every start):
 * {@code "$INST_JAVA" -jar haven-launcher.jar <pack.toml URL>}.
 * <ol>
 * <li>On the first launch only, offers to import the player's map, schematics and settings from an old CUS2 install
 * ({@link OldInstalls}).</li>
 * <li>Moves the instance to the pack's NeoForge without a question, from the next launch on ({@link LoaderSync}).</li>
 * <li>Then runs packwiz-installer-bootstrap (next to this jar) with the pack URL, which updates the pack, and exits
 * with its exit code, so a failed update still stops the launch as before.</li>
 * <li>After a good update, adds or removes the offline editor's bridge mods ({@link OfflineEditor}).</li>
 * </ol>
 * The import and the editor step can never block the update or the launch: any error there is printed and skipped.
 */
public final class HavenLauncher {
    private HavenLauncher() {
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
        Path game = gameDir();
        try {
            OldInstalls.firstRunImport(game);
        } catch (Throwable t) {
            System.err.println("Haven: import skipped: " + t);
        }
        boolean newLoader = false;
        try {
            newLoader = LoaderSync.sync(instanceDir(game), args[0]);
        } catch (Throwable t) {
            System.err.println("Haven: NeoForge check skipped: " + t);
        }
        Path bootstrap = game.resolve("packwiz-installer-bootstrap.jar");
        if (!Files.exists(bootstrap)) {
            System.err.println("Haven: " + bootstrap + " is missing; re-import the Haven instance");
            System.exit(1);
        }
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        Process update = new ProcessBuilder(java, "-jar", bootstrap.toString(), args[0])
                .directory(game.toFile()).inheritIO().start();
        int code = update.waitFor();
        if (code == 0 && !newLoader) {   // a NeoForge switch session keeps the bridge it has (it matches the old one)
            try {
                OfflineEditor.sync(game);
            } catch (Throwable t) {
                System.err.println("Haven: offline editor step skipped: " + t);
            }
        }
        System.exit(code);
    }

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
