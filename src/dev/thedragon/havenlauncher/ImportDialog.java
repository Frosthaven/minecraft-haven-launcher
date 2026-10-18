package dev.thedragon.havenlauncher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * "Import settings": copy the player's own files from any other Minecraft install on this computer (see
 * {@link OldInstalls}). One checkbox per item, greyed out when the chosen install lacks it; a note says what carries
 * over from a different modpack; "Browse…" takes any .minecraft folder the scan did not find.
 */
final class ImportDialog {
    /** {@code install} is null for "Don't ask again" without an import. */
    record Choice(OldInstalls.Install install, Set<String> items, boolean dontAskAgain) {
    }

    private ImportDialog() {
    }

    /** Returns the choice, or null for "Not now". Event thread only. */
    static Choice show(Window owner, List<OldInstalls.Install> found, Path self) {
        JDialog d = new JDialog(owner, "Import settings", JDialog.ModalityType.APPLICATION_MODAL);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(18, 22, 12, 22));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        p.add(Ui.bold("Copy your stuff from another Minecraft install", 14f), c);
        String mac = System.getProperty("os.name", "").toLowerCase().contains("mac") ? "Mac" : "PC";
        p.add(Ui.muted("Copy from  ·  " + found.size() + " other Minecraft install" + (found.size() == 1 ? "" : "s")
                + " found on this " + mac), c);

        DefaultComboBoxModel<OldInstalls.Install> model = new DefaultComboBoxModel<>(found.toArray(OldInstalls.Install[]::new));
        JComboBox<OldInstalls.Install> source = new JComboBox<>(model);
        JLabel note = Ui.small(" ");
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Pick a .minecraft folder");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) {
                Path dir = fc.getSelectedFile().toPath();
                OldInstalls.Install i = OldInstalls.describe("Folder", dir.getFileName().toString(), dir, self);
                if (i == null) {
                    note.setText("<html>Nothing to copy in that folder.</html>");
                } else {
                    model.addElement(i);
                    source.setSelectedItem(i);
                }
            }
        });
        JPanel pick = new JPanel(new BorderLayout(8, 0));
        pick.setOpaque(false);
        pick.add(source, BorderLayout.CENTER);
        pick.add(browse, BorderLayout.EAST);
        p.add(pick, c);
        c.insets = new Insets(0, 0, 14, 0);
        p.add(Ui.wrapped(note, 556), c);

        JButton all = Ui.linkButton("Everything that fits"), controlsOnly = Ui.linkButton("Controls only");
        c.insets = new Insets(0, 0, 4, 0);
        p.add(Ui.row(Ui.muted("What to copy"), Ui.actions(controlsOnly, all)), c);

        List<OldInstalls.Item> items = OldInstalls.ITEMS;
        JCheckBox[] boxes = new JCheckBox[items.size()];
        JLabel[] details = new JLabel[items.size()];
        c.insets = new Insets(0, 0, 2, 0);
        for (int i = 0; i < items.size(); i++) {
            boxes[i] = new JCheckBox(items.get(i).label());
            details[i] = Ui.small(" ");
            p.add(Ui.row(boxes[i], details[i]), c);
        }
        Runnable refresh = () -> {
            OldInstalls.Install s = (OldInstalls.Install) source.getSelectedItem();
            if (s == null) {   // nothing found: Browse… is the way in
                note.setText("<html>No other install with something to copy was found. Browse to a .minecraft folder.</html>");
                for (int i = 0; i < items.size(); i++) {
                    boxes[i].setEnabled(false);
                    boxes[i].setSelected(false);
                    details[i].setText(" ");
                }
                return;
            }
            Map<String, OldInstalls.Presence> presence = OldInstalls.presence(s);
            Set<String> defaults = OldInstalls.defaults(s, presence);
            note.setText("<html>" + OldInstalls.compat(s.kind()) + "</html>");
            for (int i = 0; i < items.size(); i++) {
                OldInstalls.Presence pr = presence.get(items.get(i).id());
                boxes[i].setEnabled(pr.present());
                boxes[i].setSelected(defaults.contains(items.get(i).id()));
                details[i].setText(pr.detail());
            }
        };
        source.addActionListener(e -> refresh.run());
        all.addActionListener(e -> {
            for (int i = 0; i < boxes.length; i++) {
                boxes[i].setSelected(boxes[i].isEnabled() && !items.get(i).id().equals("saves"));
            }
        });
        controlsOnly.addActionListener(e -> {
            for (int i = 0; i < boxes.length; i++) {
                boxes[i].setSelected(items.get(i).id().equals("options") && boxes[i].isEnabled());
            }
        });
        refresh.run();

        c.insets = new Insets(12, 0, 0, 0);
        p.add(Ui.wrapped(Ui.small("<html>Copies only. Files already in Haven are kept, and the old install is never changed. "
                + "Controls keep the key bindings, video and sound settings; resource pack lines stay as Haven set them.</html>"), 556), c);
        c.insets = new Insets(6, 0, 0, 0);
        JCheckBox dontAsk = new JCheckBox("Don't ask again");
        p.add(dontAsk, c);
        p.setPreferredSize(new Dimension(600, p.getPreferredSize().height));
        Choice[] result = new Choice[1];
        d.add(p, BorderLayout.CENTER);
        d.add(Ui.dialogButtons(d, "Import selected", () -> {
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < boxes.length; i++) {
                if (boxes[i].isEnabled() && boxes[i].isSelected()) {
                    ids.add(items.get(i).id());
                }
            }
            result[0] = new Choice((OldInstalls.Install) source.getSelectedItem(), ids, dontAsk.isSelected());
        }), BorderLayout.SOUTH);
        Ui.show(d, owner);
        if (result[0] == null && dontAsk.isSelected()) {
            return new Choice(null, Set.of(), true);
        }
        return result[0];
    }
}
