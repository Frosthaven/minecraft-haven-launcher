package dev.thedragon.havenlauncher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs packwiz-installer-bootstrap without its own window ({@code -g}) and turns its output into progress: the CLI
 * prints {@code (done/total) message} for each file and a plain phase line otherwise. Every line is also printed, so
 * Prism's log keeps the installer output. Also knows the release the update host publishes next to pack.toml
 * ({@value #RELEASE_FILE}, written by minecraft-haven-updates) and the one this instance last installed
 * ({@value #INSTALLED}).
 */
final class Updater {
    static final String BOOTSTRAP = "packwiz-installer-bootstrap.jar";
    static final String RELEASE_FILE = "haven/release.json";
    static final String INSTALLED = "haven/installed-release.txt";
    private static final Pattern COUNTED = Pattern.compile("^\\((\\d+)/(\\d+)\\)\\s*(.*)$");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    /** Where the progress goes: the window, or the console. */
    interface Progress {
        void phase(String text);

        void file(int done, int total, String text);
    }

    record Result(int code, List<String> tail) {
        /** The last line that said something, for an error message. */
        String last() {
            for (int i = tail.size() - 1; i >= 0; i--) {
                if (!tail.get(i).isBlank()) {
                    return tail.get(i);
                }
            }
            return "";
        }
    }

    private Updater() {
    }

    static Result run(Path game, String packUrl, Progress progress) throws IOException, InterruptedException {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        Process p = new ProcessBuilder(java, "-jar", game.resolve(BOOTSTRAP).toString(), "-g", packUrl)
                .directory(game.toFile()).redirectErrorStream(true).start();
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                System.out.println(line);
                tail.addLast(line);
                if (tail.size() > 20) {
                    tail.removeFirst();
                }
                Matcher m = COUNTED.matcher(line.strip());
                if (m.matches()) {
                    progress.file(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3));
                } else if (!line.isBlank()) {
                    progress.phase(line.strip());
                }
            }
        }
        return new Result(p.waitFor(), new ArrayList<>(tail));
    }

    /** The folder of pack.toml on the update host, with a trailing slash. */
    static String base(String packUrl) {
        int slash = packUrl.lastIndexOf('/');
        return slash < 0 ? packUrl + "/" : packUrl.substring(0, slash + 1);
    }

    /** The release the host serves now ("12.1.0-haven.83"), or null when the host does not publish it. */
    static String release(String packUrl) {
        try {
            String json = fetch(base(packUrl) + RELEASE_FILE, 5_000);
            Matcher m = VERSION.matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String installed(Path game) {
        try {
            String v = Files.readString(game.resolve(INSTALLED), StandardCharsets.UTF_8).strip();
            return v.isEmpty() ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    static void remember(Path game, String release) throws IOException {
        Files.createDirectories(game.resolve(INSTALLED).getParent());
        Files.writeString(game.resolve(INSTALLED), release + "\n", StandardCharsets.UTF_8);
    }

    static String fetch(String url, int timeoutMs) throws IOException {
        URLConnection c = URI.create(url).toURL().openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "haven-launcher (github.com/Frosthaven/minecraft-haven-launcher)");
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
