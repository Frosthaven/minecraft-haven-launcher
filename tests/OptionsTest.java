import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end, no screen: a pack on a local folder (pack.toml, haven/release.json, two optional .pw.toml files) and a
 * stand-in bootstrap that behaves like packwiz-installer without its window: on the first run it writes packwiz.json
 * with every optional file turned on. The launcher must put the file whose pack default is off back to off, run the
 * installer again for that, remember the release, keep the rest of the manifest, and pass the installer's exit code
 * through. A third optional file (pack default off) is listed for macOS only in config/haven-os-mods.txt: held off
 * elsewhere; on a Mac turned on once, then the player's choice stands. Run: java tests/OptionsTest.java <launcher jar>
 */
public class OptionsTest {
    static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("ok   " + what);
    }

    static int run(Path jar, Path home, Path game, Path pack, String exit) throws Exception {
        return run(jar, home, game, pack, exit, "linux");
    }

    static int run(Path jar, Path home, Path game, Path pack, String exit, String os) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home, "-Dhaven.test.os=" + os, "-jar",
                jar.toString(), pack.resolve("pack.toml").toUri().toString()).directory(game.toFile()).inheritIO();
        pb.environment().put("INST_MC_DIR", game.toString());
        pb.environment().remove("INST_DIR");
        pb.environment().remove("XDG_DATA_HOME");
        if (exit != null) {
            pb.environment().put("HAVEN_TEST_EXIT", exit);
        } else {
            pb.environment().remove("HAVEN_TEST_EXIT");
        }
        return pb.start().waitFor();
    }

    static long runs(Path game) throws Exception {
        Path f = game.resolve("bootstrap-runs.txt");
        return Files.exists(f) ? Files.readAllLines(f).size() : 0;
    }

    static String entry(String manifest, String path) {
        int at = manifest.indexOf("\"" + path + "\"");
        return manifest.substring(at, manifest.indexOf('}', manifest.indexOf('}', at) + 1) + 1);
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("haven-options-test");
        Path game = home.resolve("Haven/minecraft");
        Files.createDirectories(game);
        Files.writeString(game.resolve("haven-import.done"), "test\n");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/haven-os-mods.txt"), "# system file\nmacos mods/mac.pw.toml\n");
        Path pack = home.resolve("pack");
        Files.createDirectories(pack.resolve("mods"));
        Files.createDirectories(pack.resolve("haven"));
        Files.writeString(pack.resolve("pack.toml"), "name = \"Test\"\n\n[versions]\nminecraft = \"1.21.1\"\nneoforge = \"21.1.262\"\n");
        Files.writeString(pack.resolve("haven/release.json"), "{\"version\": \"1.0-haven.5\", \"commit\": \"abc\"}\n");
        Files.writeString(pack.resolve("mods/on.pw.toml"), "name = \"On Mod\"\nfilename = \"on.jar\"\n\n[option]\noptional = true\n"
                + "default = true\ndescription = \"Stays on.\"\n");
        Files.writeString(pack.resolve("mods/off.pw.toml"), "name = \"Off Mod\"\nfilename = \"off.jar\"\n\n[option]\noptional = true\n"
                + "default = false\ndescription = \"Off unless asked for.\"\n");
        Files.writeString(pack.resolve("mods/mac.pw.toml"), "name = \"Mac Mod\"\nfilename = \"mac.jar\"\n\n[option]\noptional = true\n"
                + "default = false\ndescription = \"Macs only.\"\n");
        // Stand-in bootstrap: prints like the CLI, writes a first manifest with every optional file on, honours HAVEN_TEST_EXIT.
        String manifest = "{\n  \"packFileHash\": {\"type\": \"sha256\", \"value\": \"p\"},\n  \"cachedFiles\": {\n"
                + "    \"mods/req.pw.toml\": {\"hash\": {\"type\": \"sha256\", \"value\": \"r\"}, \"isOptional\": false, \"optionValue\": true, \"cachedLocation\": \"mods/req.jar\"},\n"
                + "    \"mods/on.pw.toml\": {\"hash\": {\"type\": \"sha256\", \"value\": \"a\"}, \"isOptional\": true, \"optionValue\": true, \"cachedLocation\": \"mods/on.jar\"},\n"
                + "    \"mods/off.pw.toml\": {\"hash\": {\"type\": \"sha256\", \"value\": \"b\"}, \"isOptional\": true, \"optionValue\": true, \"cachedLocation\": \"mods/off.jar\"},\n"
                + "    \"mods/mac.pw.toml\": {\"hash\": {\"type\": \"sha256\", \"value\": \"c\"}, \"isOptional\": true, \"optionValue\": true, \"cachedLocation\": \"mods/mac.jar\"}\n"
                + "  },\n  \"cachedSide\": \"client\"\n}\n";
        Path src = home.resolve("Boot.java");
        Files.writeString(src, "public class Boot { public static void main(String[] a) throws Exception {"
                + " java.nio.file.Files.writeString(java.nio.file.Path.of(\"bootstrap-runs.txt\"), String.join(\" \", a) + \"\\n\","
                + " java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);"
                + " System.out.println(\"Loading manifest file...\"); System.out.println(\"(1/2) Downloaded mods/on.jar\");"
                + " System.out.println(\"(2/2) Downloaded mods/off.jar\");"
                + " java.nio.file.Path m = java.nio.file.Path.of(\"packwiz.json\");"
                + " if (!java.nio.file.Files.exists(m)) java.nio.file.Files.writeString(m, " + quote(manifest) + ");"
                + " String code = System.getenv(\"HAVEN_TEST_EXIT\"); if (code != null) System.exit(Integer.parseInt(code)); } }");
        String jdk = Path.of(System.getProperty("java.home"), "bin").toString();
        check(new ProcessBuilder(jdk + "/javac", "-d", home.resolve("boot").toString(), src.toString()).inheritIO().start()
                .waitFor() == 0, "stand-in bootstrap compiles");
        check(new ProcessBuilder(jdk + "/jar", "--create", "--file", game.resolve("packwiz-installer-bootstrap.jar").toString(),
                "--main-class", "Boot", "-C", home.resolve("boot").toString(), ".").inheritIO().start().waitFor() == 0,
                "stand-in bootstrap jar");

        check(run(jar, home, game, pack, null) == 0, "first launch exits 0");
        check(Files.readAllLines(game.resolve("bootstrap-runs.txt")).get(0).startsWith("-g "), "installer runs without its window");
        check(runs(game) == 2, "second installer pass after the defaults were applied");
        String after = Files.readString(game.resolve("packwiz.json"));
        check(entry(after, "mods/off.pw.toml").contains("\"optionValue\": false"), "new optional with default off is off");
        check(entry(after, "mods/on.pw.toml").contains("\"optionValue\": true"), "new optional with default on stays on");
        check(entry(after, "mods/mac.pw.toml").contains("\"optionValue\": false"), "a macOS-only optional is off on Linux");
        check(entry(after, "mods/req.pw.toml").contains("\"optionValue\": true") && after.contains("\"packFileHash\"")
                && after.contains("\"cachedSide\": \"client\""), "the rest of the manifest is kept");
        check(Files.readString(game.resolve("haven/installed-release.txt")).strip().equals("1.0-haven.5"), "release remembered");

        check(run(jar, home, game, pack, null) == 0 && runs(game) == 3, "second launch: one pass, nothing new");
        check(run(jar, home, game, pack, "3") == 3, "a failed update exits with the installer's code");

        Path mac = home.resolve("Mac/minecraft");
        Files.createDirectories(mac.resolve("config"));
        Files.writeString(mac.resolve("haven-import.done"), "test\n");
        Files.copy(game.resolve("config/haven-os-mods.txt"), mac.resolve("config/haven-os-mods.txt"));
        Files.copy(game.resolve("packwiz-installer-bootstrap.jar"), mac.resolve("packwiz-installer-bootstrap.jar"));
        check(run(jar, home, mac, pack, null, "macos") == 0, "first launch on a Mac exits 0");
        check(entry(Files.readString(mac.resolve("packwiz.json")), "mods/mac.pw.toml").contains("\"optionValue\": true"),
                "a macOS-only optional is turned on on a Mac");
        Path m = mac.resolve("packwiz.json");
        String turnedOff = Files.readString(m).replace(entry(Files.readString(m), "mods/mac.pw.toml"),
                entry(Files.readString(m), "mods/mac.pw.toml").replace("\"optionValue\": true", "\"optionValue\": false"));
        Files.writeString(m, turnedOff);
        check(run(jar, home, mac, pack, null, "macos") == 0
                && entry(Files.readString(m), "mods/mac.pw.toml").contains("\"optionValue\": false"),
                "a Mac player who turned it off keeps it off");
        System.out.println("options test passed");
        for (Path p : Files.walk(home).sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.delete(p);
        }
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
