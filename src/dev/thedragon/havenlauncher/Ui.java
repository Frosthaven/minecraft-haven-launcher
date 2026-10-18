package dev.thedragon.havenlauncher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Window;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/** Shared pieces of the launcher's look: colours, cards, rows and the button kinds. Event thread only. */
final class Ui {
    static final Color MUTED = new Color(0x9A9EA6), FAINT = new Color(0x7D828B), ACCENT = new Color(0xD9A441);

    private Ui() {
    }

    static JLabel bold(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, size));
        return l;
    }

    static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        return l;
    }

    static JLabel small(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FAINT);
        l.setFont(l.getFont().deriveFont(11.5f));
        return l;
    }

    /** Makes an HTML label wrap at {@code width} and ask for exactly that width, so it never widens its column. */
    static JLabel wrapped(JLabel l, int width) {
        View view = (View) l.getClientProperty(BasicHTML.propertyKey);
        if (view != null) {
            Insets in = l.getInsets();
            view.setSize(width - in.left - in.right, 0);   // lays the HTML out at this width
            int height = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + in.top + in.bottom;
            l.setPreferredSize(new Dimension(width, height));
        }
        return l;
    }

    /** Text-only action in the accent colour. */
    static JButton linkButton(String text) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setForeground(ACCENT);
        b.setFont(b.getFont().deriveFont(11.5f));
        return b;
    }

    /** The one button per window that does the main thing: accent fill, dark text. */
    static JButton primary(String text) {
        JButton b = new JButton(text);
        b.putClientProperty("FlatLaf.style",
                "background: #D9A441; foreground: #1B1C1F; hoverBackground: #E6B75C; pressedBackground: #C48F32; "
                + "focusedBackground: #D9A441; borderColor: #D9A441; hoverBorderColor: #E6B75C; focusedBorderColor: #D9A441; "
                + "disabledBackground: #4A4336; disabledText: #8B8273; disabledBorderColor: #4A4336");
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        return b;
    }

    static JPanel card() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(14, 16, 14, 16));
        p.putClientProperty("FlatLaf.style", "arc: 14; background: #2A2C31");
        p.setOpaque(true);
        return p;
    }

    static JComponent left(JComponent c) {
        JPanel w = new JPanel(new BorderLayout());
        w.setOpaque(false);
        w.add(c, BorderLayout.WEST);
        return w;
    }

    static JComponent row(JComponent west, JComponent east) {
        JPanel w = new JPanel(new BorderLayout(12, 0));
        w.setOpaque(false);
        w.add(west, BorderLayout.WEST);
        w.add(east, BorderLayout.EAST);
        return w;
    }

    /** Button group, flush to its edge, each button at its natural size. */
    static JPanel actions(int align, JComponent... items) {
        JPanel p = new JPanel(new FlowLayout(align, 0, 0));
        p.setOpaque(false);
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                p.add(Box.createHorizontalStrut(8));
            }
            p.add(items[i]);
        }
        return p;
    }

    static JPanel actions(JComponent... items) {
        return actions(FlowLayout.RIGHT, items);
    }

    /** "Not now" closes; the accent button runs {@code onOk} and closes. */
    static JPanel dialogButtons(JDialog d, String ok, Runnable onOk) {
        JButton cancel = new JButton("Not now");
        cancel.addActionListener(e -> d.dispose());
        JButton okBtn = primary(ok);
        okBtn.addActionListener(e -> {
            onOk.run();
            d.dispose();
        });
        JPanel b = actions(cancel, okBtn);
        b.setBorder(new EmptyBorder(0, 22, 16, 22));
        d.getRootPane().setDefaultButton(okBtn);
        return b;
    }

    /** Packs, centres over the owner and shows a modal dialog (returns when it closes). */
    static void show(JDialog d, Window owner) {
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
