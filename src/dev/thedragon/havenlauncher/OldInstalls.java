package dev.thedragon.havenlauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The player's other Minecraft installs on this computer and what can be copied from them into Haven: the CurseForge
 * app, Prism (every sibling of this instance, the known roots, the Flatpak), the Modrinth app and the vanilla
 * launcher, on Windows, macOS and Linux. Copies only; the old install is never changed. A folder adds the files Haven
 * does not have yet; a single file (options.txt, servers.dat) is replaced. servers.dat is offered only when it lists
 * Haven; options.txt from another pack loses its resource pack lines, which Haven sets itself.
 * <p>
 * First launch: {@link HavenLauncher} asks once. After Import, "Don't ask again", or when nothing is found, a marker
 * file ({@value #DONE}) in the game folder stops it for good; "Not now" asks again next launch. Test hook:
 * {@code -Dhaven.import.auto=first} imports the best match's default items without a window.
 */
final class OldInstalls {
    static final String DONE = "haven-import.done";
    static final String HAVEN_HOST = "thedragon.dev";

    enum Kind { SAME_PACK, OTHER_PACK, VANILLA }

    /** One thing to copy. {@code universal}: carries over from any pack; else tied to a pack's worlds and item list. */
    record Item(String id, String label, String path, boolean universal) {
    }

    static final List<Item> ITEMS = List.of(
            new Item("options", "Controls and settings", "options.txt", true),
            new Item("servers", "Server list", "servers.dat", true),
            new Item("screenshots", "Screenshots", "screenshots", true),
            new Item("schematics", "Create schematics", "schematics", true),
            new Item("blueprints", "Axiom blueprints", "blueprints", true),
            new Item("journeymap", "JourneyMap map and waypoints", "journeymap", false),
            new Item("ftbchunks", "FTB Chunks map and claims", "local/ftbchunks", false),
            new Item("jei", "JEI bookmarks and history", "config/jei/world", false),
            new Item("saves", "Single-player worlds", "saves", false));

    record Install(String launcher, String name, Path game, Kind kind, long modified) {
        @Override
        public String toString() {
            return launcher + " · " + name;
        }
    }

    /** What one install has for one item: the text next to the checkbox, and whether there is anything to copy. */
    record Presence(String detail, boolean present) {
    }

    private OldInstalls() {
    }

    /** Installs with something to copy: the same pack first, then the most recently used. */
    static List<Install> find(Path self) {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<Path> curseforge = new ArrayList<>(), prism = new ArrayList<>(), modrinth = new ArrayList<>(), vanilla = new ArrayList<>();
        if (os.contains("win")) {
            curseforge.add(Path.of(home, "curseforge", "minecraft", "Instances"));
            String appdata = System.getenv("APPDATA");
            if (appdata != null) {
                prism.add(Path.of(appdata, "PrismLauncher", "instances"));
                modrinth.add(Path.of(appdata, "ModrinthApp", "profiles"));
                modrinth.add(Path.of(appdata, "com.modrinth.theseus", "profiles"));
                vanilla.add(Path.of(appdata, ".minecraft"));
            }
        } else if (os.contains("mac")) {
            Path support = Path.of(home, "Library", "Application Support");
            curseforge.add(Path.of(home, "Documents", "curseforge", "minecraft", "Instances"));
            prism.add(support.resolve("PrismLauncher").resolve("instances"));
            modrinth.add(support.resolve("ModrinthApp").resolve("profiles"));
            modrinth.add(support.resolve("com.modrinth.theseus").resolve("profiles"));
            vanilla.add(support.resolve("minecraft"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            Path data = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
            prism.add(data.resolve("PrismLauncher").resolve("instances"));
            prism.add(Path.of(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher", "instances"));
            modrinth.add(data.resolve("ModrinthApp").resolve("profiles"));
            modrinth.add(data.resolve("com.modrinth.theseus").resolve("profiles"));
            modrinth.add(Path.of(home, ".var", "app", "com.modrinth.ModrinthApp", "data", "ModrinthApp", "profiles"));
            vanilla.add(Path.of(home, ".minecraft"));
            vanilla.add(Path.of(home, ".var", "app", "com.mojang.Minecraft", ".minecraft"));
        }
        String instDir = System.getenv("INST_DIR");   // this instance: its siblings are the player's other instances
        if (instDir != null && !instDir.isBlank()) {
            prism.add(Path.of(instDir).toAbsolutePath().getParent());
        }
        List<Install> found = new ArrayList<>();
        for (Path root : curseforge) {
            for (Path inst : children(root)) {
                add(found, describe("CurseForge app", inst.getFileName().toString(), inst, self));
            }
        }
        for (Path root : prism) {
            for (Path inst : children(root)) {
                for (String sub : List.of("minecraft", ".minecraft")) {
                    add(found, describe("Prism Launcher", prismName(inst), inst.resolve(sub), self));
                }
            }
        }
        for (Path root : modrinth) {
            for (Path profile : children(root)) {
                add(found, describe("Modrinth app", profile.getFileName().toString(), profile, self));
            }
        }
        for (Path dir : vanilla) {
            add(found, describe("Minecraft Launcher", ".minecraft", dir, self));
        }
        found.sort(Comparator.comparing((Install i) -> i.kind() != Kind.SAME_PACK).thenComparing(i -> -i.modified()));
        return found;
    }

    private static void add(List<Install> found, Install i) {
        if (i != null && found.stream().noneMatch(f -> f.game().equals(i.game()))) {
            found.add(i);
        }
    }

    /** An install for a game folder, or null when it is not one, is this instance, or holds nothing to copy. */
    static Install describe(String launcher, String name, Path game, Path self) {
        Path g = game.toAbsolutePath().normalize();
        if (!Files.isDirectory(g) || g.equals(self.toAbsolutePath().normalize())) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Kind kind = lower.contains("ultimate selection") || lower.contains("cus2") || lower.contains("haven") ? Kind.SAME_PACK
                : hasJars(g.resolve("mods")) ? Kind.OTHER_PACK : Kind.VANILLA;
        Install i = new Install(launcher, name, g, kind, modified(g));
        return presence(i).values().stream().anyMatch(Presence::present) ? i : null;
    }

    /** Per item id: what this install has. */
    static Map<String, Presence> presence(Install i) {
        Map<String, Presence> map = new LinkedHashMap<>();
        for (Item item : ITEMS) {
            Path p = i.game().resolve(item.path());
            if (!Files.exists(p)) {
                map.put(item.id(), new Presence("not found", false));
            } else if (item.id().equals("servers")) {
                boolean haven = listsHaven(p);
                map.put(item.id(), new Presence(haven ? "servers.dat · lists Haven" : "servers.dat · no Haven entry", haven));
            } else if (Files.isRegularFile(p)) {
                map.put(item.id(), new Presence(item.path(), true));
            } else {
                long[] stat = stat(p);
                String what = item.id().equals("saves") ? plural(countDirs(p), "world") : plural(stat[0], "file");
                map.put(item.id(), new Presence(item.path() + "/ · " + (stat[0] == 0 ? "empty" : what + ", " + human(stat[1])),
                        stat[0] > 0));
            }
        }
        return map;
    }

    /** What is ticked by default: what is there, never worlds, and from another pack only the items any pack can use. */
    static Set<String> defaults(Install i, Map<String, Presence> presence) {
        Set<String> ids = new LinkedHashSet<>();
        for (Item item : ITEMS) {
            if (presence.get(item.id()).present() && !item.id().equals("saves") && (item.universal() || i.kind() == Kind.SAME_PACK)) {
                ids.add(item.id());
            }
        }
        return ids;
    }

    static String compat(Kind k) {
        return switch (k) {
            case SAME_PACK -> "Same modpack. Everything carries over as is.";
            case OTHER_PACK -> "Different modpack. Controls, servers, screenshots and schematics carry over cleanly. "
                    + "Map data, chunk claims and JEI bookmarks belong to that pack's worlds and item list, so they are off by default.";
            case VANILLA -> "Vanilla install. Controls, servers and screenshots carry over. Its worlds open in Haven, but they would gain mod content.";
        };
    }

    /** Copies the chosen items into the game folder; returns the number of files copied. */
    static int copy(Install from, Path to, Set<String> ids) throws IOException {
        int[] count = {0};
        for (Item item : ITEMS) {
            Path src = from.game().resolve(item.path());
            if (!ids.contains(item.id()) || !Files.exists(src)) {
                continue;
            }
            Path dst = to.resolve(item.path());
            if (Files.isDirectory(src)) {
                Files.walkFileTree(src, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                        Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                        Path target = dst.resolve(src.relativize(file).toString());
                        if (!Files.exists(target)) {   // Haven's own newer files stay
                            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                            count[0]++;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else if (item.id().equals("servers") && !listsHaven(src)) {
                continue;   // keep Haven's list
            } else if (item.id().equals("options") && from.kind() != Kind.SAME_PACK) {
                Files.createDirectories(dst.getParent());
                List<String> kept = Files.readAllLines(src, StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.startsWith("resourcePacks:") && !l.startsWith("incompatibleResourcePacks:")).toList();
                Files.write(dst, kept, StandardCharsets.UTF_8);
                count[0]++;
            } else {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                count[0]++;
            }
        }
        return count[0];
    }

    private static boolean listsHaven(Path serversDat) {
        try {
            return new String(Files.readAllBytes(serversDat), StandardCharsets.ISO_8859_1).contains(HAVEN_HOST);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasJars(Path mods) {
        if (!Files.isDirectory(mods)) {
            return false;
        }
        try (Stream<Path> s = Files.list(mods)) {
            return s.anyMatch(p -> p.toString().endsWith(".jar"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Prism's display name from instance.cfg, else the folder name. */
    private static String prismName(Path inst) {
        try {
            for (String line : Files.readAllLines(inst.resolve("instance.cfg"), StandardCharsets.UTF_8)) {
                if (line.startsWith("name=") && line.length() > 5) {
                    return line.substring(5).strip();
                }
            }
        } catch (IOException ignored) {
            // no instance.cfg: an older or hand-made instance
        }
        return inst.getFileName().toString();
    }

    private static long modified(Path game) {
        long newest = 0;
        for (String p : List.of("options.txt", "journeymap", "saves")) {
            try {
                newest = Math.max(newest, Files.getLastModifiedTime(game.resolve(p)).toMillis());
            } catch (IOException ignored) {
                // not there
            }
        }
        return newest;
    }

    /** {files, bytes} under a folder. */
    private static long[] stat(Path dir) {
        long[] s = {0, 0};
        try (Stream<Path> w = Files.walk(dir)) {
            w.filter(Files::isRegularFile).forEach(f -> {
                s[0]++;
                try {
                    s[1] += Files.size(f);
                } catch (IOException ignored) {
                    // counted, size unknown
                }
            });
        } catch (IOException ignored) {
            // unreadable folder: shown as empty
        }
        return s;
    }

    private static long countDirs(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<Path> children(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes + 512) / 1024 + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return (bytes + 512 * 1024) / (1024 * 1024) + " MB";
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
