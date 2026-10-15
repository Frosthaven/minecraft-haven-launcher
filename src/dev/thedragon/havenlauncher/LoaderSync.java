package dev.thedragon.havenlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves the instance to the pack's NeoForge without a question, effective from the player's next launch.
 * <p>
 * Prism reads mmc-pack.json when a launch starts and writes its own copy back about two seconds after the game starts,
 * so a change made during the pre-launch step (packwiz's "Update" button included) is undone. So: before the update
 * this writes the pack.toml NeoForge into the NeoForge entry (the bare {@code {"uid": "net.neoforged", "version": ...}}
 * entry packwiz writes; Prism fills in the rest), which also keeps packwiz from asking, and starts a background helper
 * ({@code --keep-neoforge}) that writes it again after Prism's copy lands. This session runs on the old NeoForge; the
 * next launch loads the new one. Only when the Minecraft version is the same: a Minecraft change goes through
 * packwiz's dialog.
 */
final class LoaderSync {
    static final String KEEP = "--keep-neoforge";
    static final int KEEP_SECONDS = 180;
    private static final Pattern MINECRAFT = Pattern.compile("(?m)^\\s*minecraft\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern NEOFORGE = Pattern.compile("(?m)^\\s*neoforge\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");

    private LoaderSync() {
    }

    /** Returns true when the NeoForge entry was changed (this launch still runs the old NeoForge). */
    static boolean sync(Path instance, String packUrl) throws IOException {
        Path mmc = instance.resolve("mmc-pack.json");
        if (!Files.exists(mmc)) {
            return false;
        }
        String pack = fetch(packUrl);
        Matcher mc = MINECRAFT.matcher(pack), nf = NEOFORGE.matcher(pack);
        if (!mc.find() || !nf.find() || !write(mmc, mc.group(1), nf.group(1))) {
            return false;
        }
        Path jar = Path.of(URI.create(LoaderSync.class.getProtectionDomain().getCodeSource().getLocation().toString()));
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "javaw.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        new ProcessBuilder(java, "-cp", jar.toString(), HavenLauncher.class.getName(), KEEP, mmc.toString(),
                mc.group(1), nf.group(1)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        System.out.println("Haven: NeoForge -> " + nf.group(1) + " (used from your next launch)");
        return true;
    }

    /** The background helper: for a few minutes, puts the pack's NeoForge back whenever Prism writes its old copy. */
    static void keep(Path mmc, String minecraft, String neoforge) throws Exception {
        String override = System.getenv("HAVEN_KEEP_SECONDS");   // tests shorten the watch
        long seconds = override != null && override.matches("\\d+") ? Long.parseLong(override) : KEEP_SECONDS;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            Thread.sleep(1000);
            try {
                if (write(mmc, minecraft, neoforge)) {
                    Files.writeString(mmc.resolveSibling("haven-neoforge.log"),
                            Instant.now() + " wrote NeoForge " + neoforge + " back after Prism's save\n",
                            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (IOException e) {
                // Prism may be writing the file right now; try again next second
            }
        }
    }

    /** Sets the NeoForge entry to `neoforge` when Minecraft matches and NeoForge differs; true when it wrote. */
    static boolean write(Path mmc, String minecraft, String neoforge) throws IOException {
        String json = Files.readString(mmc, StandardCharsets.UTF_8);
        int[] mcEntry = component(json, "net.minecraft"), nfEntry = component(json, "net.neoforged");
        if (mcEntry == null || nfEntry == null) {
            return false;
        }
        Matcher have = VERSION.matcher(json.substring(mcEntry[0], mcEntry[1]));
        Matcher loader = VERSION.matcher(json.substring(nfEntry[0], nfEntry[1]));
        if (!have.find() || !have.group(1).equals(minecraft) || !loader.find() || loader.group(1).equals(neoforge)) {
            return false;   // Minecraft differs (packwiz asks) or NeoForge already matches
        }
        String entry = "{\"uid\": \"net.neoforged\", \"version\": \"" + neoforge + "\"}";
        Path tmp = mmc.resolveSibling("mmc-pack.json.haven");
        Files.writeString(tmp, json.substring(0, nfEntry[0]) + entry + json.substring(nfEntry[1]), StandardCharsets.UTF_8);
        Files.move(tmp, mmc, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /** Start and end (exclusive) of the components object whose "uid" is {@code uid}, or null. */
    static int[] component(String json, String uid) {
        Matcher m = Pattern.compile("\"uid\"\\s*:\\s*\"" + Pattern.quote(uid) + "\"").matcher(json);
        if (!m.find()) {
            return null;
        }
        int start = -1;
        for (int i = m.start(), depth = 0; i >= 0; i--) {   // the "{" that opens the object holding this key
            char c = json.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                    break;
                }
                depth--;
            }
        }
        if (start < 0) {
            return null;
        }
        for (int i = start, depth = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return new int[] {start, i + 1};
            }
        }
        return null;
    }

    private static String fetch(String url) throws IOException {
        URLConnection c = URI.create(url).toURL().openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
