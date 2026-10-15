package dev.thedragon.havenlauncher;

import java.awt.GraphicsEnvironment;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BorderLayout;

/**
 * First-launch import of a player's personal files from an old Create Ultimate Selection 2 install: the CurseForge
 * app (Windows, macOS) or Prism (all systems, including the Flatpak and any sibling of this instance). Copies only;
 * the old install is never changed. Asks once: after Import, "Don't ask again", or when nothing is found, a marker file
 * ({@value #DONE}) in the new game folder stops it for good; "Not now" asks again next launch.
 * Test hook: {@code -Dhaven.import.auto=first} imports the best match without a window.
 */
final class OldInstalls {
    static final String DONE = "haven-import.done";
    /** What is copied, relative to the game folder. servers.dat only when it already lists Haven. */
    static final List<String> ITEMS = List.of("journeymap", "schematics", "options.txt", "config/jei/world",
            "screenshots", "saves", "servers.dat");
    static final String HAVEN_HOST = "thedragon.dev";

    record Install(String label, Path game, boolean cus2, long modified) {
        @Override
        public String toString() {
            return label;
        }
    }

    private OldInstalls() {
    }

    static void firstRunImport(Path game) throws IOException {
        Path done = game.resolve(DONE);
        if (Files.exists(done)) {
            return;
        }
        List<Install> found = find(game);
        if (found.isEmpty()) {
            Files.writeString(done, "no old install found\n");
            System.out.println("Haven: no old install to import from");
            return;
        }
        Install pick;
        if ("first".equals(System.getProperty("haven.import.auto"))) {
            pick = found.get(0);
        } else if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Haven: no screen to ask on; import skipped for now");
            return;
        } else {
            pick = ask(found);
            if (pick == null) {
                return;
            }
            if (pick.game() == null) {          // "Don't ask again"
                Files.writeString(done, "declined\n");
                return;
            }
        }
        int files = copy(pick.game(), game);
        Files.writeString(done, "imported " + files + " files from " + pick.game() + "\n");
        System.out.println("Haven: imported " + files + " files from " + pick.game());
    }

    /** Old installs that hold something worth importing, the CUS2 ones first, then the most recently used. */
    static List<Install> find(Path self) {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<Path> curseforge = new ArrayList<>();
        List<Path> prism = new ArrayList<>();
        if (os.contains("win")) {
            curseforge.add(Path.of(home, "curseforge", "minecraft", "Instances"));
            String appdata = System.getenv("APPDATA");
            if (appdata != null) {
                prism.add(Path.of(appdata, "PrismLauncher", "instances"));
            }
        } else if (os.contains("mac")) {
            curseforge.add(Path.of(home, "Documents", "curseforge", "minecraft", "Instances"));
            prism.add(Path.of(home, "Library", "Application Support", "PrismLauncher", "instances"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            prism.add((xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share"))
                    .resolve("PrismLauncher").resolve("instances"));
            prism.add(Path.of(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher",
                    "instances"));
        }
        String instDir = System.getenv("INST_DIR");   // this instance: its siblings are the player's other instances
        if (instDir != null && !instDir.isBlank()) {
            prism.add(Path.of(instDir).toAbsolutePath().getParent());
        }
        List<Install> found = new ArrayList<>();
        for (Path root : curseforge) {
            for (Path inst : children(root)) {
                add(found, "CurseForge: " + inst.getFileName(), inst, self);
            }
        }
        for (Path root : prism) {
            for (Path inst : children(root)) {
                for (String sub : List.of("minecraft", ".minecraft")) {
                    add(found, "Prism: " + inst.getFileName(), inst.resolve(sub), self);
                }
            }
        }
        found.sort(Comparator.comparing((Install i) -> !i.cus2()).thenComparing(i -> -i.modified()));
        return found;
    }

    private static void add(List<Install> found, String label, Path game, Path self) {
        Path g = game.toAbsolutePath().normalize();
        if (!Files.isDirectory(g) || g.equals(self.toAbsolutePath().normalize())
                || found.stream().anyMatch(i -> i.game().equals(g))) {
            return;
        }
        if (!Files.isDirectory(g.resolve("journeymap")) && !Files.isDirectory(g.resolve("schematics"))) {
            return;   // nothing a player would miss
        }
        String name = label.toLowerCase(Locale.ROOT);
        boolean cus2 = name.contains("ultimate selection") || name.contains("cus2") || name.contains("haven");
        long modified = 0;
        try {
            modified = Files.getLastModifiedTime(g.resolve("journeymap")).toMillis();
        } catch (IOException ignored) {
            // no journeymap folder: sorts last among equals
        }
        found.add(new Install(label, g, cus2, modified));
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

    /** The import window. Returns the chosen install, an install with a null game for "Don't ask again", or null. */
    private static Install ask(List<Install> found) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look works too
        }
        JComboBox<Install> box = new JComboBox<>(found.toArray(Install[]::new));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html><b>Bring your stuff into Haven?</b><br><br>"
                + "Copies your map and waypoints (JourneyMap), Create schematics, keybinds and settings,<br>"
                + "JEI bookmarks, screenshots and single-player worlds from your old install.<br>"
                + "Your old install is not changed.<br><br>Import from:</html>"), BorderLayout.NORTH);
        panel.add(box, BorderLayout.CENTER);
        String[] options = {"Import", "Not now", "Don't ask again"};
        int choice = JOptionPane.showOptionDialog(null, panel, "Haven", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            return (Install) box.getSelectedItem();
        }
        return choice == 2 ? new Install("", null, false, 0) : null;
    }

    /** Copies ITEMS from the old game folder into the new one (replacing); returns the number of files copied. */
    static int copy(Path from, Path to) throws IOException {
        int[] count = {0};
        for (String item : ITEMS) {
            Path src = from.resolve(item);
            if (!Files.exists(src)) {
                continue;
            }
            if (item.equals("servers.dat") && !new String(Files.readAllBytes(src), StandardCharsets.ISO_8859_1)
                    .contains(HAVEN_HOST)) {
                continue;   // keep the new list (it has Haven) unless the old one already did
            }
            Path dst = to.resolve(item);
            if (Files.isDirectory(src)) {
                Files.walkFileTree(src, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                        Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                        Files.copy(file, dst.resolve(src.relativize(file).toString()),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        count[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                count[0]++;
            }
        }
        return count[0];
    }
}
