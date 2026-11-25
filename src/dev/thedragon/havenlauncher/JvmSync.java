package dev.thedragon.havenlauncher;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gives the instance the pack's Java options (config/haven-jvm-args.txt, delivered by the update), effective from the
 * player's next launch.
 * <p>
 * Prism reads instance.cfg when a launch starts, before the pre-launch step, and writes its own copy back after the game
 * starts and again when it ends (lastLaunchTime, totalTimePlayed), so an edit made during the pre-launch step is undone
 * twice. Measured 2026-11-25: a value in the file when a launch starts is used and then kept by Prism, also by a Prism
 * that stayed open. So: write it now and start a background helper ({@code --keep-jvm-args}) that writes it again after
 * each of Prism's saves until the end-of-session save (totalTimePlayed changes), then ends.
 * <p>
 * The player's own options win: the file changes only while JvmArgs is empty or still holds what Haven wrote last
 * (haven/jvm-args.applied). The options are tried first with this Java (the instance's, "$INST_JAVA"), so options that
 * Java refuses never reach the game: the game would not start.
 */
final class JvmSync {
    static final String KEEP = "--keep-jvm-args";
    static final String PACK_FILE = "config/haven-jvm-args.txt";
    static final String APPLIED = "haven/jvm-args.applied";
    static final long KEEP_HOURS = 24;   // a session longer than this keeps the options for the launch after next

    private JvmSync() {
    }

    /** Returns what it did, for the console. */
    static String sync(Path game, Path instance) throws IOException, InterruptedException {
        Path cfg = instance.resolve("instance.cfg");
        if (!Files.exists(cfg)) {
            return "Java options skipped: no instance.cfg";
        }
        String wanted = packArgs(game.resolve(PACK_FILE));
        Path appliedFile = game.resolve(APPLIED);
        String applied = Files.exists(appliedFile) ? Files.readString(appliedFile, StandardCharsets.UTF_8).strip() : "";
        String have = value(Files.readString(cfg, StandardCharsets.UTF_8), "JvmArgs");
        have = have == null ? "" : have.strip();
        if (have.equals(wanted)) {
            remember(appliedFile, wanted);
            return "Java options up to date";
        }
        if (!have.isEmpty() && !have.equals(applied)) {
            return "your own Java options kept (" + have + ")";
        }
        if (!wanted.isEmpty()) {
            String refused = refused(wanted);
            if (refused != null) {
                return "Java options skipped: this Java refuses them (" + refused + ")";
            }
        }
        if (!write(cfg, wanted)) {
            return "Java options up to date";
        }
        remember(appliedFile, wanted);
        Path jar = Path.of(URI.create(JvmSync.class.getProtectionDomain().getCodeSource().getLocation().toString()));
        new ProcessBuilder(java(), "-cp", jar.toString(), HavenLauncher.class.getName(), KEEP, cfg.toString(), wanted)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return wanted.isEmpty() ? "Java options removed (from your next launch)"
                : "Java options -> " + wanted + " (from your next launch)";
    }

    /** The background helper: puts the options back after each of Prism's saves, up to its end-of-session save. */
    static void keep(Path cfg, String wanted) throws Exception {
        String override = System.getenv("HAVEN_KEEP_SECONDS");   // tests shorten the watch
        long seconds = override != null && override.matches("\\d+") ? Long.parseLong(override) : KEEP_HOURS * 3600;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        String played = value(Files.readString(cfg, StandardCharsets.UTF_8), "totalTimePlayed");
        while (System.nanoTime() < end) {
            Thread.sleep(1000);
            try {
                String text = Files.readString(cfg, StandardCharsets.UTF_8);
                boolean sessionEnded = !java.util.Objects.equals(played, value(text, "totalTimePlayed"));
                if (write(cfg, wanted)) {
                    Files.writeString(cfg.resolveSibling("haven-jvm-args.log"),
                            Instant.now() + " wrote Java options back after Prism's save\n", StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
                if (sessionEnded) {
                    return;   // Prism's last save of this session is behind us; its next launch reads the file
                }
            } catch (IOException e) {
                // Prism may be writing the file right now; try again next second
            }
        }
    }

    /** The options in the pack file: lines joined by spaces, '#' comments and blank lines left out; "" when none. */
    static String packArgs(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                parts.add(t);
            }
        }
        return String.join(" ", parts);
    }

    /** Sets OverrideJavaArgs and JvmArgs (both removed when {@code wanted} is empty); true when the file changed. */
    static boolean write(Path cfg, String wanted) throws IOException {
        String text = Files.readString(cfg, StandardCharsets.UTF_8);
        String have = value(text, "JvmArgs");
        if (wanted.isEmpty() ? have == null : wanted.equals(have == null ? null : have.strip())
                && "true".equals(value(text, "OverrideJavaArgs"))) {
            return false;
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r?\n", -1)) {
            if (!line.startsWith("JvmArgs=") && !line.startsWith("OverrideJavaArgs=")) {
                out.append(line).append('\n');
            }
        }
        while (out.length() > 1 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n') {
            out.setLength(out.length() - 1);
        }
        if (!wanted.isEmpty()) {
            out.append("OverrideJavaArgs=true\n").append("JvmArgs=\"").append(wanted.replace("\\", "\\\\")
                    .replace("\"", "\\\"")).append("\"\n");
        }
        Path tmp = cfg.resolveSibling("instance.cfg.haven");
        Files.writeString(tmp, out, StandardCharsets.UTF_8);
        Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /** A key's value in Prism's instance.cfg, without Prism's quotes and escapes; null when the key is absent. */
    static String value(String text, String key) {
        for (String line : text.split("\r?\n")) {
            if (line.startsWith(key + "=")) {
                String v = line.substring(key.length() + 1);
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                }
                return v;
            }
        }
        return null;
    }

    /** Null when this Java starts with the options, else its first error line. */
    static String refused(String options) throws IOException, InterruptedException {
        // java.exe, not javaw.exe: javaw shows a message box when it refuses an option
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString()));
        cmd.addAll(List.of(options.split("\\s+")));
        cmd.add("-version");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "no answer in 30 s";
        }
        return p.exitValue() == 0 ? null : out.strip().lines().findFirst().orElse("exit " + p.exitValue());
    }

    private static void remember(Path file, String wanted) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, wanted + "\n", StandardCharsets.UTF_8);
    }

    private static String java() {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "javaw.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }
}
