import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end: a fake home with two old Prism instances (a CUS2 one and an unrelated one), a new Haven instance next
 * to them, and a stand-in packwiz bootstrap that records its arguments. Run: java tests/ImportTest.java <launcher jar>
 */
public class ImportTest {
    static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("ok   " + what);
    }

    static int run(Path jar, Path home, Path inst, Path game) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Dhaven.import.auto=first",
                "-Duser.home=" + home, "-jar", jar.toString(), "https://example.invalid/key/current/pack.toml")
                .directory(game.toFile()).inheritIO();
        pb.environment().put("INST_DIR", inst.toString());
        pb.environment().put("INST_MC_DIR", game.toString());
        pb.environment().remove("XDG_DATA_HOME");
        return pb.start().waitFor();
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("haven-import-test");
        Path instances = home.resolve(".local/share/PrismLauncher/instances");
        Path old = instances.resolve("Create Ultimate Selection 2/minecraft");
        Path other = instances.resolve("Vanilla/minecraft");
        Files.createDirectories(old.resolve("journeymap/data/mp/Haven"));
        Files.writeString(old.resolve("journeymap/data/mp/Haven/waypoints.json"), "{\"home\":1}");
        Files.createDirectories(old.resolve("schematics"));
        Files.writeString(old.resolve("schematics/bridge.nbt"), "nbt");
        Files.writeString(old.resolve("options.txt"), "key_key.jump:key.keyboard.space");
        Files.writeString(old.resolve("servers.dat"), "....minecraft.thedragon.dev....");
        Files.createDirectories(old.resolve("mods"));
        Files.writeString(old.resolve("mods/create.jar"), "not copied");
        Files.createDirectories(other.resolve("journeymap"));
        Files.writeString(other.resolve("options.txt"), "WRONG INSTANCE");
        Path inst = instances.resolve("Haven");
        Path game = inst.resolve("minecraft");
        Files.createDirectories(game);
        // Stand-in bootstrap: a single-file program that writes its arguments to bootstrap-args.txt.
        Path src = home.resolve("Boot.java");
        Files.writeString(src, "public class Boot { public static void main(String[] a) throws Exception {"
                + " java.nio.file.Files.writeString(java.nio.file.Path.of(\"bootstrap-args.txt\"), String.join(\" \", a)); } }");
        Path classes = home.resolve("boot");
        String jdk = Path.of(System.getProperty("java.home"), "bin").toString();
        check(new ProcessBuilder(jdk + "/javac", "-d", classes.toString(), src.toString()).inheritIO().start().waitFor() == 0,
                "stand-in bootstrap compiles");
        check(new ProcessBuilder(jdk + "/jar", "--create", "--file", game.resolve("packwiz-installer-bootstrap.jar").toString(),
                "--main-class", "Boot", "-C", classes.toString(), ".").inheritIO().start().waitFor() == 0, "stand-in bootstrap jar");

        check(run(jar, home, inst, game) == 0, "first launch exits 0");
        check(Files.readString(game.resolve("journeymap/data/mp/Haven/waypoints.json")).equals("{\"home\":1}"), "map copied");
        check(Files.exists(game.resolve("schematics/bridge.nbt")), "schematics copied");
        check(Files.readString(game.resolve("options.txt")).contains("key.jump"), "settings from the CUS2 instance");
        check(Files.exists(game.resolve("servers.dat")), "server list copied (it lists Haven)");
        check(!Files.exists(game.resolve("mods/create.jar")), "mods not copied");
        check(Files.exists(old.resolve("options.txt")) && Files.exists(old.resolve("journeymap")), "old install untouched");
        check(Files.readString(game.resolve("bootstrap-args.txt")).equals("https://example.invalid/key/current/pack.toml"),
                "updater started with the pack URL");
        check(Files.readString(game.resolve("haven-import.done")).startsWith("imported"), "marked done");

        Files.writeString(game.resolve("options.txt"), "changed in game");
        check(run(jar, home, inst, game) == 0, "second launch exits 0");
        check(Files.readString(game.resolve("options.txt")).equals("changed in game"), "second launch does not import again");
        System.out.println("all ok");
        for (Path p : Files.walk(home).sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.delete(p);
        }
    }
}
