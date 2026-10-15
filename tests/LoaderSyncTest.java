import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end: an instance whose mmc-pack.json (as Prism saves it, cached fields included) names an older NeoForge.
 * The launcher moves it to pack.toml's NeoForge, and its background helper puts it back after "Prism" writes its old
 * copy (as Prism does two seconds after the game starts). It leaves the file alone when NeoForge already matches and
 * when the Minecraft version differs (packwiz asks then). Run: java tests/LoaderSyncTest.java <launcher jar>
 */
public class LoaderSyncTest {
    static final String MMC = """
            {"components":[{"cachedName":"Minecraft","cachedRequires":[{"suggests":"3.3.3","uid":"org.lwjgl3"}],\
            "cachedVersion":"1.21.1","important":true,"uid":"net.minecraft","version":"1.21.1"},\
            {"cachedName":"LWJGL 3","cachedVersion":"3.3.3","cachedVolatile":true,"dependencyOnly":true,"uid":"org.lwjgl3",\
            "version":"3.3.3"},{"cachedName":"NeoForge","cachedRequires":[{"equals":"1.21.1","uid":"net.minecraft"}],\
            "cachedVersion":"21.1.243","uid":"net.neoforged","version":"21.1.243"}],"formatVersion":1}""";
    static final String NEW_ENTRY = "{\"uid\": \"net.neoforged\", \"version\": \"21.1.262\"}";

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
        pb.environment().put("HAVEN_KEEP_SECONDS", "6");
        pb.environment().remove("XDG_DATA_HOME");
        return pb.start().waitFor();
    }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("haven-loader-test");
        Path inst = home.resolve("Haven");
        Path game = inst.resolve("minecraft");
        Files.createDirectories(game);
        Files.writeString(game.resolve("haven-import.done"), "test\n");
        Path src = home.resolve("Boot.java");
        Files.writeString(src, "public class Boot { public static void main(String[] a) { } }");
        String jdk = Path.of(System.getProperty("java.home"), "bin").toString();
        check(new ProcessBuilder(jdk + "/javac", "-d", home.resolve("boot").toString(), src.toString()).inheritIO().start()
                .waitFor() == 0, "stand-in bootstrap compiles");
        check(new ProcessBuilder(jdk + "/jar", "--create", "--file", game.resolve("packwiz-installer-bootstrap.jar").toString(),
                "--main-class", "Boot", "-C", home.resolve("boot").toString(), ".").inheritIO().start().waitFor() == 0,
                "stand-in bootstrap jar");
        Path mmc = inst.resolve("mmc-pack.json");
        Path pack = home.resolve("pack.toml");

        Files.writeString(mmc, MMC);
        Files.writeString(pack, "name = \"Haven\"\n[versions]\nminecraft = \"1.21.1\"\nneoforge = \"21.1.262\"\n");
        check(run(jar, home, inst, game, pack) == 0, "newer NeoForge: launch exits 0");
        String after = Files.readString(mmc);
        check(after.contains(NEW_ENTRY) && !after.contains("21.1.243"), "NeoForge entry moved to 21.1.262");
        check(after.startsWith("{\"components\":[{\"cachedName\":\"Minecraft\"") && after.endsWith("],\"formatVersion\":1}")
                && after.contains("\"uid\":\"org.lwjgl3\",\"version\":\"3.3.3\"}"), "other components untouched");
        Thread.sleep(1500);
        Files.writeString(mmc, MMC);                           // "Prism" writes its old copy back
        Thread.sleep(2500);
        check(Files.readString(mmc).contains(NEW_ENTRY), "helper put 21.1.262 back after Prism's save");
        Thread.sleep(4000);                                    // the helper has ended (HAVEN_KEEP_SECONDS=6)

        check(run(jar, home, inst, game, pack) == 0, "same NeoForge: launch exits 0");
        check(Files.readString(mmc).contains(NEW_ENTRY), "same NeoForge: still 21.1.262");

        Files.writeString(mmc, MMC);
        Files.writeString(pack, "name = \"Haven\"\n[versions]\nminecraft = \"26.3\"\nneoforge = \"26.3.0.84\"\n");
        check(run(jar, home, inst, game, pack) == 0, "other Minecraft: launch exits 0");
        Thread.sleep(2500);
        check(Files.readString(mmc).equals(MMC), "other Minecraft: file unchanged (packwiz asks)");
        System.out.println("loader sync test passed");
    }
}
