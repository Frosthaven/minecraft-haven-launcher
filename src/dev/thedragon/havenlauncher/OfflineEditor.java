package dev.thedragon.havenlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The pack's "Offline Editor (Axiom)" option is one optional mod (Axiom), but Axiom is a Fabric mod and needs three
 * bridge mods to run on NeoForge. packwiz cannot tie them to one checkbox, so after each update this step adds them
 * while the editor's jar is in mods/, and removes them once it is gone.
 * <p>
 * The list is {@value #LIST} in the pack (written by the pack's tools):
 * <pre>
 * trigger Axiom-                       a jar in mods/ whose name starts with this turns the bridge on
 * mod &lt;file&gt; &lt;sha512&gt; &lt;url&gt;           one line per bridge mod
 * </pre>
 * {@value #RECORD} in the game folder lists what this step installed, so it only ever removes its own files. Errors
 * are printed and never stop the launch: without the bridge, NeoForge just skips the Fabric jar.
 */
final class OfflineEditor {
    static final String LIST = "config/haven-offline-editor.txt";
    static final String RECORD = "haven-offline-editor.installed";

    record Mod(String file, String sha512, String url) {
    }

    private OfflineEditor() {
    }

    static void sync(Path game) throws IOException {
        Path list = game.resolve(LIST);
        Path mods = game.resolve("mods");
        Path record = game.resolve(RECORD);
        String trigger = null;
        List<Mod> bridge = new ArrayList<>();
        if (Files.exists(list)) {
            for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                String[] f = line.strip().split("\\s+");
                if (f.length == 2 && f[0].equals("trigger")) {
                    trigger = f[1];
                } else if (f.length == 4 && f[0].equals("mod") && safeName(f[1])) {
                    bridge.add(new Mod(f[1], f[2].toLowerCase(), f[3]));
                }
            }
        }
        boolean on = trigger != null && Files.isDirectory(mods) && hasJar(mods, trigger);
        List<Mod> wanted = on ? bridge : List.of();
        Set<String> installed = new LinkedHashSet<>();
        if (Files.exists(record)) {
            Files.readAllLines(record, StandardCharsets.UTF_8).stream().map(String::strip).filter(OfflineEditor::safeName)
                    .forEach(installed::add);
        }
        for (String file : List.copyOf(installed)) {      // remove what we installed and no longer want
            if (wanted.stream().noneMatch(m -> m.file().equals(file))) {
                Files.deleteIfExists(mods.resolve(file));
                installed.remove(file);
                System.out.println("Haven: offline editor: removed " + file);
            }
        }
        for (Mod m : wanted) {
            Path jar = mods.resolve(m.file());
            if (Files.exists(jar) && sha512(Files.readAllBytes(jar)).equals(m.sha512())) {
                installed.add(m.file());
                continue;
            }
            try {
                byte[] data = download(m.url());
                String got = sha512(data);
                if (!got.equals(m.sha512())) {
                    System.err.println("Haven: offline editor: " + m.file() + " does not match its SHA-512; skipped");
                    continue;
                }
                Path part = mods.resolve(m.file() + ".part");
                Files.write(part, data);
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
                installed.add(m.file());
                System.out.println("Haven: offline editor: added " + m.file());
            } catch (IOException e) {
                System.err.println("Haven: offline editor: could not download " + m.file() + ": " + e);
            }
        }
        if (installed.isEmpty()) {
            Files.deleteIfExists(record);
        } else {
            Files.writeString(record, String.join("\n", installed) + "\n");
        }
    }

    /** A plain file name: never a path, so a list or record line cannot reach outside mods/. */
    static boolean safeName(String name) {
        return name.endsWith(".jar") && !name.contains("/") && !name.contains("\\") && !name.startsWith(".");
    }

    private static boolean hasJar(Path mods, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(mods)) {
            return s.map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith(prefix) && n.endsWith(".jar"));
        }
    }

    private static byte[] download(String url) throws IOException {
        URLConnection c = URI.create(url).toURL().openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setRequestProperty("User-Agent", "haven-launcher (github.com/Frosthaven/minecraft-haven-launcher)");
        try (InputStream in = c.getInputStream()) {
            return in.readAllBytes();
        }
    }

    static String sha512(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
