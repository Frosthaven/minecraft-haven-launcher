package dev.thedragon.havenlauncher;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the Haven launcher itself up to date. Prism always starts haven-launcher.jar in the game folder (the copy
 * Haven.zip brought, possibly old); the pack delivers the newest launcher to {@value #DELIVERED} with every update.
 * When the two differ, this runs the delivered one: it is copied to haven/run/&lt;hash&gt;.jar first and that copy runs,
 * with the same JVM options and arguments, so packwiz can still replace {@value #DELIVERED} during the update (Windows
 * cannot replace a jar that is running). Its exit code is ours. A run copy never delegates again.
 */
final class SelfUpdate {
    static final String DELIVERED = "haven/haven-launcher.jar";
    static final String RUN = "haven/run";

    private SelfUpdate() {
    }

    /** The delegated launcher's exit code, or null when this launcher is already the delivered one. */
    static Integer delegate(Path game, String[] args) throws Exception {
        Path self = Path.of(URI.create(SelfUpdate.class.getProtectionDomain().getCodeSource().getLocation().toString()))
                .toAbsolutePath().normalize();
        Path runDir = game.resolve(RUN).toAbsolutePath().normalize();
        Path delivered = game.resolve(DELIVERED);
        if (self.startsWith(runDir) || !Files.isRegularFile(delivered) || !Files.isRegularFile(self)) {
            return null;
        }
        String hash = OfflineEditor.sha512(Files.readAllBytes(delivered));
        if (hash.equals(OfflineEditor.sha512(Files.readAllBytes(self)))) {
            return null;
        }
        Files.createDirectories(runDir);
        Path run = runDir.resolve(hash.substring(0, 16) + ".jar");
        if (!Files.exists(run)) {
            Path part = runDir.resolve(run.getFileName() + ".part");
            Files.copy(delivered, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, run, StandardCopyOption.REPLACE_EXISTING);
        }
        try (DirectoryStream<Path> old = Files.newDirectoryStream(runDir, "*.jar")) {
            for (Path p : old) {
                if (!p.equals(run)) {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                        // still running somewhere (Windows); next launch removes it
                    }
                }
            }
        }
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", exe).toString()));
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-jar");
        cmd.add(run.toString());
        cmd.addAll(List.of(args));
        System.out.println("Haven: running the updated launcher (" + run.getFileName() + ")");
        return new ProcessBuilder(cmd).directory(game.toFile()).inheritIO().start().waitFor();
    }
}
