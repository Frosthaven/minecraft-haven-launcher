package dev.thedragon.havenlauncher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * The pack's optional mods, as packwiz-installer keeps them in {@value #MANIFEST}: each optional file's
 * {@code optionValue}. Without its window the installer turns every optional file it meets for the first time on
 * (its CLI handler "accepts" them), so after each run {@link #applyDefaults} puts the files that were new in that run
 * back to the pack's {@code [option] default}, read from their .pw.toml on the update host. Names and descriptions for
 * the dialog come from the same files. Edits keep the rest of the manifest byte for byte.
 * <p>
 * {@value #OS_LIST} in the pack limits optional files to one system, one line each ({@code macos mods/x.pw.toml};
 * systems {@code macos}, {@code windows}, {@code linux}): on any other system the file is held off after every update
 * (so the installer removes it) and left out of the dialog. On its own system it is turned on once ({@value #OS_RECORD}
 * in the game folder lists those), and the player's later choice stands. Its pack default is off, so a launcher before
 * 2.1.1 (the first session after the update still runs the old one) keeps it off everywhere.
 */
final class OptionalMods {
    static final String MANIFEST = "packwiz.json";
    static final String OS_LIST = "config/haven-os-mods.txt";
    static final String OS_RECORD = "haven-os-mods.applied";
    private static final Pattern KEY = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)+\\.pw\\.toml)\"\\s*:\\s*\\{");
    private static final Pattern OPTIONAL = Pattern.compile("\"isOptional\"\\s*:\\s*true");
    private static final Pattern VALUE = Pattern.compile("\"optionValue\"\\s*:\\s*(true|false)");
    private static final Pattern NAME = Pattern.compile("(?m)^name\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern DEFAULT = Pattern.compile("(?m)^default\\s*=\\s*(true|false)");
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^description\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** What the pack says about one optional file. */
    record Meta(String name, String description, boolean defaultOn) {
    }

    record Entry(String path, Meta meta, boolean on) {
    }

    private OptionalMods() {
    }

    /** path -> optionValue for every optional file in the manifest (empty before the first update). */
    static Map<String, Boolean> read(Path game) throws IOException {
        Map<String, Boolean> values = new LinkedHashMap<>();
        Path manifest = game.resolve(MANIFEST);
        if (!Files.exists(manifest)) {
            return values;
        }
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        Matcher k = KEY.matcher(json);
        while (k.find()) {
            int[] obj = object(json, k.end() - 1);
            if (obj == null) {
                continue;
            }
            String body = json.substring(obj[0], obj[1]);
            if (OPTIONAL.matcher(body).find()) {
                Matcher v = VALUE.matcher(body);
                values.put(unescape(k.group(1)), !v.find() || Boolean.parseBoolean(v.group(1)));
            }
        }
        return values;
    }

    /** Sets optionValue for the given files; returns the paths whose value changed. */
    static Set<String> write(Path game, Map<String, Boolean> wanted) throws IOException {
        Path manifest = game.resolve(MANIFEST);
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        Set<String> changed = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        int pos = 0;
        Matcher k = KEY.matcher(json);
        while (k.find(pos)) {
            int[] obj = object(json, k.end() - 1);
            if (obj == null) {
                break;
            }
            String path = unescape(k.group(1));
            String body = json.substring(obj[0], obj[1]);
            Boolean want = wanted.get(path);
            if (want != null && OPTIONAL.matcher(body).find()) {
                Matcher v = VALUE.matcher(body);
                String edited;
                if (v.find()) {
                    edited = Boolean.parseBoolean(v.group(1)) == want ? body
                            : body.substring(0, v.start(1)) + want + body.substring(v.end(1));
                } else {
                    edited = body.substring(0, 1) + "\"optionValue\": " + want + ", " + body.substring(1);
                }
                if (!edited.equals(body)) {
                    changed.add(path);
                }
                out.append(json, pos, obj[0]).append(edited);
            } else {
                out.append(json, pos, obj[1]);
            }
            pos = obj[1];
        }
        out.append(json.substring(pos));
        if (!changed.isEmpty()) {
            Path tmp = manifest.resolveSibling(MANIFEST + ".haven");
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return changed;
    }

    /**
     * Optional files that were not in {@code before} (new in the last run, so the CLI turned them on) go to their pack
     * default. Returns the paths it changed; a non-empty result needs one more installer run.
     */
    static Set<String> applyDefaults(Path game, String baseUrl, Set<String> before) throws IOException {
        Map<String, Boolean> now = read(game);
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        Set<String> otherOs = otherOs(game), thisOs = thisOs(game);
        Path record = game.resolve(OS_RECORD);
        Set<String> applied = new LinkedHashSet<>(Files.exists(record)
                ? Files.readAllLines(record, StandardCharsets.UTF_8).stream().map(String::strip).toList() : List.of());
        boolean recorded = false;
        for (String path : now.keySet()) {
            if (otherOs.contains(path)) {
                wanted.put(path, false);
            } else if (thisOs.contains(path) && !applied.contains(path)) {
                wanted.put(path, true);   // once: then the player's choice stands
                recorded = applied.add(path);
            } else if (!before.contains(path)) {
                wanted.put(path, meta(baseUrl, path).defaultOn());
            }
        }
        Set<String> changed = wanted.isEmpty() ? Set.of() : write(game, wanted);
        if (recorded) {
            Files.writeString(record, String.join("\n", applied) + "\n", StandardCharsets.UTF_8);
        }
        return changed;
    }

    /** The pack's name, description and default for one optional file; a name made from the path when offline. */
    static Meta meta(String baseUrl, String path) {
        String fallback = path.substring(path.lastIndexOf('/') + 1).replace(".pw.toml", "");
        try {
            String toml = Updater.fetch(baseUrl + path, 10_000);
            Matcher n = NAME.matcher(toml), d = DEFAULT.matcher(toml), desc = DESCRIPTION.matcher(toml);
            return new Meta(n.find() ? unescape(n.group(1)) : fallback, desc.find() ? unescape(desc.group(1)) : "",
                    d.find() && Boolean.parseBoolean(d.group(1)));
        } catch (IOException e) {
            System.err.println("Haven: could not read " + path + " from the update host: " + e);
            return new Meta(fallback, "", false);
        }
    }

    static List<Entry> entries(Path game, String baseUrl) throws IOException {
        List<Entry> list = new ArrayList<>();
        Set<String> otherOs = otherOs(game);
        for (Map.Entry<String, Boolean> e : read(game).entrySet()) {
            if (otherOs.contains(e.getKey())) {
                continue;
            }
            list.add(new Entry(e.getKey(), meta(baseUrl, e.getKey()), e.getValue()));
        }
        return list;
    }

    /** The optional files {@value #OS_LIST} gives to another system than this one. */
    static Set<String> otherOs(Path game) throws IOException {
        return osListed(game, false);
    }

    /** The optional files {@value #OS_LIST} gives to this system. */
    static Set<String> thisOs(Path game) throws IOException {
        return osListed(game, true);
    }

    private static Set<String> osListed(Path game, boolean mine) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Path list = game.resolve(OS_LIST);
        if (Files.exists(list)) {
            String os = os();
            for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                String[] f = line.strip().split("\\s+");
                if (f.length == 2 && !f[0].startsWith("#") && f[0].equals(os) == mine) {
                    out.add(f[1]);
                }
            }
        }
        return out;
    }

    /** {@code macos}, {@code windows} or {@code linux} ({@code -Dhaven.test.os} for tests). */
    static String os() {
        String name = System.getProperty("haven.test.os", System.getProperty("os.name", "")).toLowerCase(java.util.Locale.ROOT);
        return name.contains("mac") ? "macos" : name.contains("win") ? "windows" : "linux";
    }

    /** The Optional mods window. Returns the new choice per path, or null for "Not now". Event thread only. */
    static Map<String, Boolean> dialog(Window owner, List<Entry> entries) {
        JDialog d = new JDialog(owner, "Optional mods", JDialog.ModalityType.APPLICATION_MODAL);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(18, 22, 12, 22));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 10, 0);
        p.add(Ui.bold("Choose what Haven adds on top of Create Ultimate Selection 2", 14f), c);
        c.insets = new Insets(0, 0, 4, 0);
        Map<String, JCheckBox> boxes = new LinkedHashMap<>();
        for (Entry e : entries) {
            JCheckBox cb = new JCheckBox(e.meta().name(), e.on());
            cb.setFont(cb.getFont().deriveFont(java.awt.Font.BOLD));
            boxes.put(e.path(), cb);
            p.add(cb, c);
            if (!e.meta().description().isBlank()) {
                JLabel desc = Ui.muted("<html>" + escapeHtml(e.meta().description()) + "</html>");
                desc.setBorder(new EmptyBorder(0, 24, 8, 0));
                p.add(Ui.wrapped(desc, 476), c);
            }
        }
        c.insets = new Insets(10, 0, 0, 0);
        p.add(Ui.wrapped(Ui.small("<html>Changes apply right away, before the game starts. "
                + "You can come back any time from the launcher window.</html>"), 476), c);
        p.setPreferredSize(new Dimension(520, p.getPreferredSize().height));
        Map<String, Boolean>[] result = new Map[1];
        d.add(p, BorderLayout.CENTER);
        d.add(Ui.dialogButtons(d, "Save", () -> {
            Map<String, Boolean> r = new LinkedHashMap<>();
            boxes.forEach((path, cb) -> r.put(path, cb.isSelected()));
            result[0] = r;
        }), BorderLayout.SOUTH);
        Ui.show(d, owner);
        return result[0];
    }

    /** Start and end (exclusive) of the JSON object whose "{" is at {@code open}, or null. */
    static int[] object(String json, int open) {
        boolean inString = false;
        for (int i = open, depth = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return new int[] {open, i + 1};
            }
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
