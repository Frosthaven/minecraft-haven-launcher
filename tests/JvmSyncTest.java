import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end: the pack's Java options (config/haven-jvm-args.txt) reach Prism's instance.cfg, and the background helper
 * puts them back after "Prism" saves its old copy (after the game starts, and with a new totalTimePlayed when it ends),
 * then stops. A player's own options are kept; a pack change and a removal follow; options this Java refuses are
 * skipped. Run: java tests/JvmSyncTest.java <launcher jar>
 */
public class JvmSyncTest {
    static final String CFG = "[General]\nInstanceType=OneSix\nname=Haven\nMaxMemAlloc=10000\ntotalTimePlayed=100\n";
    static final String FLAGS = "-XX:+UseG1GC -XX:MaxGCPauseMillis=50";

    static void check(boolean ok, String what) {
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("ok   " + what);
    }

    static int run(Path jar, Path home, Path inst, Path game, Path pack) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home, "-jar",
                jar.toString(), pack.toUri().toString()).directory(game.toFile()).inheritIO();
        pb.environment().put("INST_DIR", inst.toString());
        pb.environment().put("INST_MC_DIR", game.toString());
        pb.environment().put("HAVEN_KEEP_SECONDS", "8");
        pb.environment().remove("XDG_DATA_HOME");
        return pb.start().waitFor();
    }

    static String jvmArgs(Path cfg) throws Exception {
        for (String line : Files.readAllLines(cfg)) {
            if (line.startsWith("JvmArgs=")) {
                return line.substring(8);
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("haven-jvm-test");
        Path inst = home.resolve("Haven");
        Path game = inst.resolve("minecraft");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("haven-import.done"), "test\n");
        Path src = home.resolve("Boot.java");
        Files.writeString(src, "public class Boot { public static void main(String[] a) { } }");
        String jdk = Path.of(System.getProperty("java.home"), "bin").toString();
        check(new ProcessBuilder(jdk + "/javac", "-d", home.resolve("boot").toString(), src.toString()).inheritIO().start()
                .waitFor() == 0, "stand-in bootstrap compiles");
        check(new ProcessBuilder(jdk + "/jar", "--create", "--file", game.resolve("packwiz-installer-bootstrap.jar").toString(),
                "--main-class", "Boot", "-C", home.resolve("boot").toString(), ".").inheritIO().start().waitFor() == 0,
                "stand-in bootstrap jar");
        Path cfg = inst.resolve("instance.cfg");
        Path pack = home.resolve("pack.toml");
        Path flags = game.resolve("config/haven-jvm-args.txt");
        Files.writeString(pack, "name = \"Haven\"\n[versions]\nminecraft = \"1.21.1\"\nneoforge = \"21.1.262\"\n");

        Files.writeString(cfg, CFG);
        Files.writeString(flags, "# Java options for every Haven game\n-XX:+UseG1GC\n-XX:MaxGCPauseMillis=50\n");
        check(run(jar, home, inst, game, pack) == 0, "fresh instance: launch exits 0");
        String after = Files.readString(cfg);
        check(("\"" + FLAGS + "\"").equals(jvmArgs(cfg)) && after.contains("OverrideJavaArgs=true\n"),
                "fresh instance: JvmArgs set, quoted as Prism writes it");
        check(after.startsWith(CFG), "other settings untouched");
        Thread.sleep(1500);
        Files.writeString(cfg, CFG.replace("MaxMemAlloc", "lastLaunchTime=1\nMaxMemAlloc"));   // Prism's start save
        Thread.sleep(2500);
        check(("\"" + FLAGS + "\"").equals(jvmArgs(cfg)), "helper put the options back after Prism's start save");
        Files.writeString(cfg, CFG.replace("totalTimePlayed=100", "totalTimePlayed=160"));      // Prism's end save
        Thread.sleep(2500);
        check(("\"" + FLAGS + "\"").equals(jvmArgs(cfg)), "helper put the options back after Prism's end save");
        Files.writeString(cfg, CFG.replace("totalTimePlayed=100", "totalTimePlayed=160"));
        Thread.sleep(2500);
        check(jvmArgs(cfg) == null, "helper ended after the end save (writes after it are left alone)");

        Files.writeString(cfg, CFG + "OverrideJavaArgs=true\nJvmArgs=-XX:+UseZGC\n");
        check(run(jar, home, inst, game, pack) == 0, "own options: launch exits 0");
        Thread.sleep(2000);
        check("-XX:+UseZGC".equals(jvmArgs(cfg)), "own options: kept");

        Files.writeString(cfg, CFG + "OverrideJavaArgs=true\nJvmArgs=\"" + FLAGS + "\"\n");
        Files.writeString(flags, "-XX:+UseG1GC -XX:MaxGCPauseMillis=40\n");
        check(run(jar, home, inst, game, pack) == 0, "pack change: launch exits 0");
        check("\"-XX:+UseG1GC -XX:MaxGCPauseMillis=40\"".equals(jvmArgs(cfg)), "pack change: options follow the pack");
        Thread.sleep(9000);                                                                   // that helper has ended

        Files.delete(flags);
        check(run(jar, home, inst, game, pack) == 0, "removed from the pack: launch exits 0");
        after = Files.readString(cfg);
        check(jvmArgs(cfg) == null && !after.contains("OverrideJavaArgs"), "removed from the pack: options removed");
        Thread.sleep(9000);

        Files.writeString(cfg, CFG);
        Files.writeString(flags, "-XX:+HavenNoSuchOption\n");
        check(run(jar, home, inst, game, pack) == 0, "refused options: launch exits 0");
        check(Files.readString(cfg).equals(CFG), "refused options: instance.cfg unchanged");
        System.out.println("jvm sync test passed");
    }
}
