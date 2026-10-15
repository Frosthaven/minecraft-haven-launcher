import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * End-to-end: the game folder's haven-launcher.jar differs from the one the pack delivered (haven/haven-launcher.jar).
 * The launcher copies the delivered one to haven/run/ and runs that copy, which runs the update exactly once and does
 * not delegate again; with identical jars nothing is delegated. Run: java tests/SelfUpdateTest.java <launcher jar>
 */
public class SelfUpdateTest {
    static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("ok   " + what);
    }

    static int run(Path home, Path game) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home, "-jar",
                game.resolve("haven-launcher.jar").toString(), "https://example.invalid/key/current/pack.toml")
                .directory(game.toFile()).inheritIO();
        pb.environment().put("INST_MC_DIR", game.toString());
        pb.environment().remove("INST_DIR");
        pb.environment().remove("XDG_DATA_HOME");
        return pb.start().waitFor();
    }

    static long runs(Path game) throws Exception {
        Path f = game.resolve("bootstrap-runs.txt");
        return Files.exists(f) ? Files.readAllLines(f).size() : 0;
    }

    static long runCopies(Path game) throws Exception {
        Path dir = game.resolve("haven/run");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar")).count();
        }
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("haven-selfupdate-test");
        Path game = home.resolve("Haven/minecraft");
        Files.createDirectories(game.resolve("haven"));
        Files.writeString(game.resolve("haven-import.done"), "test\n");
        String jdk = Path.of(System.getProperty("java.home"), "bin").toString();
        // Stand-in bootstrap: appends one line per run.
        Path src = home.resolve("Boot.java");
        Files.writeString(src, "public class Boot { public static void main(String[] a) throws Exception {"
                + " java.nio.file.Files.writeString(java.nio.file.Path.of(\"bootstrap-runs.txt\"), \"run\\n\","
                + " java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } }");
        check(new ProcessBuilder(jdk + "/javac", "-d", home.resolve("boot").toString(), src.toString()).inheritIO().start()
                .waitFor() == 0, "stand-in bootstrap compiles");
        check(new ProcessBuilder(jdk + "/jar", "--create", "--file", game.resolve("packwiz-installer-bootstrap.jar").toString(),
                "--main-class", "Boot", "-C", home.resolve("boot").toString(), ".").inheritIO().start().waitFor() == 0,
                "stand-in bootstrap jar");
        // The game folder's launcher, and a delivered launcher with the same code but different bytes (a marker entry).
        Files.copy(jar, game.resolve("haven-launcher.jar"), StandardCopyOption.REPLACE_EXISTING);
        Path delivered = game.resolve("haven/haven-launcher.jar");
        Files.copy(jar, delivered, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(home.resolve("marker.txt"), "delivered");
        check(new ProcessBuilder(jdk + "/jar", "--update", "--file", delivered.toString(), "-C", home.toString(),
                "marker.txt").inheritIO().start().waitFor() == 0, "delivered launcher differs");

        check(run(home, game) == 0, "differing launchers: exits 0");
        check(runCopies(game) == 1, "delivered launcher copied to haven/run");
        check(runs(game) == 1, "update ran exactly once (the run copy did not delegate again)");
        check(run(home, game) == 0 && runCopies(game) == 1 && runs(game) == 2, "second launch reuses the run copy");

        Files.copy(game.resolve("haven-launcher.jar"), delivered, StandardCopyOption.REPLACE_EXISTING);
        check(run(home, game) == 0 && runs(game) == 3, "same launchers: exits 0, update runs");
        System.out.println("self-update test passed");
    }
}
