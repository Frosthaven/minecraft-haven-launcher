import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * End-to-end: a game folder with the pack's bridge list, a stand-in packwiz bootstrap, and bridge "downloads" served
 * from file: URLs. Ticking the editor (its jar appears) adds the bridge; a bad hash is skipped without stopping the
 * launch; unticking removes only what the launcher added. Run: java tests/OfflineEditorTest.java <launcher jar>
 */
public class OfflineEditorTest {
    static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("ok   " + what);
    }

    static int run(Path jar, Path home, Path game) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home, "-jar",
                jar.toString(), "https://example.invalid/key/current/pack.toml").directory(game.toFile()).inheritIO();
        pb.environment().put("INST_MC_DIR", game.toString());
        pb.environment().remove("INST_DIR");
        pb.environment().remove("XDG_DATA_HOME");
        return pb.start().waitFor();
    }

    static String sha512(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(b));
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("haven-editor-test");
        Path game = home.resolve("Haven/minecraft");
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("haven-import.done"), "test\n");     // no import window
        // Stand-in bootstrap that does nothing.
        Path src = home.resolve("Boot.java");
        Files.writeString(src, "public class Boot { public static void main(String[] a) { } }");
        String jdk = Path.of(System.getProperty("java.home"), "bin").toString();
        check(new ProcessBuilder(jdk + "/javac", "-d", home.resolve("boot").toString(), src.toString()).inheritIO().start()
                .waitFor() == 0, "stand-in bootstrap compiles");
        check(new ProcessBuilder(jdk + "/jar", "--create", "--file", game.resolve("packwiz-installer-bootstrap.jar").toString(),
                "--main-class", "Boot", "-C", home.resolve("boot").toString(), ".").inheritIO().start().waitFor() == 0,
                "stand-in bootstrap jar");
        // Two bridge mods served from file: URLs; the second one's listed hash is wrong.
        Path served = Files.createDirectories(home.resolve("served"));
        byte[] good = "connector".getBytes(), bad = "fabric-api".getBytes();
        Files.write(served.resolve("connector.jar"), good);
        Files.write(served.resolve("fabric-api.jar"), bad);
        Files.writeString(game.resolve("config/haven-offline-editor.txt"), String.join("\n",
                "# test list", "trigger Axiom-",
                "mod connector.jar " + sha512(good) + " " + served.resolve("connector.jar").toUri(),
                "mod fabric-api.jar " + "0".repeat(128) + " " + served.resolve("fabric-api.jar").toUri(),
                "mod ../escape.jar " + sha512(good) + " " + served.resolve("connector.jar").toUri()) + "\n");
        Files.writeString(mods.resolve("create.jar"), "not ours");

        check(run(jar, home, game) == 0, "editor off: launch exits 0");
        check(!Files.exists(mods.resolve("connector.jar")), "editor off: no bridge");

        Files.writeString(mods.resolve("Axiom-6.1.3-for-MC1.21.1.jar"), "axiom");
        check(run(jar, home, game) == 0, "editor on: launch exits 0 despite a bad hash");
        check(Files.readString(mods.resolve("connector.jar")).equals("connector"), "editor on: bridge mod added");
        check(!Files.exists(mods.resolve("fabric-api.jar")), "editor on: mod with a wrong hash skipped");
        check(!Files.exists(home.resolve("Haven/escape.jar")), "editor on: a path in the list is refused");
        check(Files.readString(game.resolve("haven-offline-editor.installed")).strip().equals("connector.jar"),
                "editor on: record lists only what was added");

        Files.delete(mods.resolve("Axiom-6.1.3-for-MC1.21.1.jar"));
        check(run(jar, home, game) == 0, "editor off again: launch exits 0");
        check(!Files.exists(mods.resolve("connector.jar")), "editor off again: bridge removed");
        check(Files.exists(mods.resolve("create.jar")), "editor off again: other mods untouched");
        check(!Files.exists(game.resolve("haven-offline-editor.installed")), "editor off again: record cleared");
        System.out.println("offline editor test passed");
    }
}
