package dev.thedragon.havenlauncher;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * The launcher's window: the pack's title art ({@value #ART}, delivered by the pack; a plain band before the first
 * update), the update card (headline, progress, current file, release) and the footer (Online Map, Import settings,
 * Optional mods…, Play now with the auto-start countdown). Methods may be called from any thread. {@link #await()}
 * blocks until the player starts the game (0), the countdown ends (0) or the window is closed (1).
 */
final class LauncherWindow implements Updater.Progress {
    static final String MAP_URL = "https://map.minecraft.thedragon.dev/";
    static final String ART = "config/fancymenu/assets/1x/bg_haven.png";
    static final int COUNTDOWN_SECONDS = 10;
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final JFrame frame;
    private final JLabel status, detail, release;
    private final JProgressBar bar;
    private final JButton play, retryBtn, optional, importBtn;
    private final CountDownLatch decided = new CountDownLatch(1);
    private volatile int exitCode = 1;
    private Runnable onImport = () -> { }, onOptional = () -> { };
    private Timer countdown;
    private int secondsLeft, paused;

    static LauncherWindow open(Path game) throws Exception {
        LauncherWindow[] w = new LauncherWindow[1];
        SwingUtilities.invokeAndWait(() -> w[0] = new LauncherWindow(game));
        return w[0];
    }

    private LauncherWindow(Path game) {
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#D9A441"));
        if (MAC) {
            FlatMacDarkLaf.setup();
        } else {
            FlatDarkLaf.setup();
        }
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("Button.arc", 12);
        UIManager.put("Component.arc", 10);

        frame = new JFrame("Haven");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                decide(1);   // closing the window stops the launch, as a failed update does
            }
        });
        frame.setLayout(new BorderLayout());
        frame.add(new Hero(art(game)), BorderLayout.NORTH);

        JPanel card = Ui.card();
        status = Ui.bold("Checking the pack for updates", 14f);
        release = Ui.small(" ");
        card.add(Ui.row(status, release));
        card.add(Box.createVerticalStrut(10));
        bar = new JProgressBar(0, 100);
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(10, 10));
        card.add(bar);
        card.add(Box.createVerticalStrut(8));
        detail = Ui.muted(" ");
        JPanel detailRow = new JPanel(new BorderLayout());   // CENTER: a long file name is cut with "…", not widened
        detailRow.setOpaque(false);
        detailRow.add(detail, BorderLayout.CENTER);
        card.add(detailRow);
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(new EmptyBorder(6, 22, 10, 22));
        body.add(card, BorderLayout.NORTH);
        frame.add(body, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(4, 22, 16, 22));
        JButton onlineMap = new JButton("Online Map");   // the server's map, behind the Haven login
        onlineMap.addActionListener(e -> browse(MAP_URL));
        importBtn = new JButton("Import settings");
        importBtn.addActionListener(e -> onImport.run());
        optional = new JButton("Optional mods…");
        optional.setEnabled(false);   // known after the first update
        optional.addActionListener(e -> onOptional.run());
        footer.add(Ui.actions(FlowLayout.LEFT, onlineMap, importBtn, optional), BorderLayout.WEST);
        retryBtn = new JButton("Try again");
        retryBtn.setVisible(false);
        play = Ui.primary("Updating…");
        play.setEnabled(false);
        play.addActionListener(e -> decide(0));
        footer.add(Ui.actions(retryBtn, play), BorderLayout.EAST);
        frame.add(footer, BorderLayout.SOUTH);

        frame.pack();   // height follows the fonts of the OS, width follows the title band
        frame.setResizable(false);   // a fixed launcher window: tiling desktops float it
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.getRootPane().setDefaultButton(play);
    }

    // ---- what the launch flow tells the window ----------------------------------------------------

    void onImport(Runnable r) {
        onImport = r;
    }

    void onOptional(Runnable r) {
        onOptional = r;
    }

    /** An update (or another pass) starts: progress unknown, Play off. */
    void busy(String headline) {
        onEdt(() -> {
            stopCountdown();
            status.setText(headline);
            detail.setText(" ");
            bar.setIndeterminate(true);
            play.setText("Updating…");
            play.setEnabled(false);
            retryBtn.setVisible(false);
            optional.setEnabled(false);
        });
    }

    @Override
    public void phase(String text) {
        onEdt(() -> detail.setText(text));
    }

    @Override
    public void file(int done, int total, String text) {
        onEdt(() -> {
            bar.setIndeterminate(false);
            bar.setMaximum(Math.max(total, 1));
            bar.setValue(done);
            detail.setText(text);
        });
    }

    void release(String text) {
        onEdt(() -> release.setText(text.isBlank() ? " " : text));
    }

    /** The update is done: the game starts after the countdown, or on Play now. */
    void ready(String headline, String text) {
        onEdt(() -> {
            status.setText(headline);
            detail.setText(text);
            bar.setIndeterminate(false);
            bar.setValue(bar.getMaximum());
            optional.setEnabled(true);
            retryBtn.setVisible(false);
            play.setEnabled(true);
            play.requestFocusInWindow();
            startCountdown();
        });
    }

    /** The update did not finish: show why, offer another try and "Play anyway". */
    void failed(String headline, String why, Runnable retry) {
        onEdt(() -> {
            stopCountdown();
            status.setText(headline);
            detail.setText(why);
            bar.setIndeterminate(false);
            bar.setValue(0);
            for (var l : retryBtn.getActionListeners()) {
                retryBtn.removeActionListener(l);
            }
            retryBtn.setVisible(retry != null);
            if (retry != null) {
                retryBtn.addActionListener(e -> retry.run());
            }
            optional.setEnabled(true);
            play.setText("Play anyway");
            play.setEnabled(true);
        });
    }

    /** A line under the headline for things outside the update (an import, for example). */
    void say(String text) {
        onEdt(() -> detail.setText(text));
    }

    /** Runs a dialog on the event thread while the countdown waits; returns its result. */
    <T> T dialog(Callable<T> body) throws Exception {
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        onEdt(() -> paused++);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = body.call();
                } catch (Exception e) {
                    error[0] = e;
                }
            });
        } finally {
            onEdt(() -> paused--);
        }
        if (error[0] != null) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    Window frame() {
        return frame;
    }

    /** Blocks until the player decided; the exit code for Prism. */
    int await() throws InterruptedException {
        decided.await();
        return exitCode;
    }

    // ---- inside ----------------------------------------------------------------------------------

    private void decide(int code) {
        exitCode = code;
        stopCountdown();
        decided.countDown();
        frame.setVisible(false);
        frame.dispose();
    }

    private void startCountdown() {
        stopCountdown();
        secondsLeft = COUNTDOWN_SECONDS;
        play.setText("Play now (" + secondsLeft + ")");
        countdown = new Timer(1000, e -> {
            if (paused > 0) {
                return;
            }
            secondsLeft--;
            if (secondsLeft <= 0) {
                decide(0);
            } else {
                play.setText("Play now (" + secondsLeft + ")");
            }
        });
        countdown.start();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private static BufferedImage art(Path game) {
        try {
            Path p = game.resolve(ART);
            return Files.isRegularFile(p) ? ImageIO.read(p.toFile()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void browse(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {   // some Linux desktops lack the Desktop API: xdg-open does it
            try {
                new ProcessBuilder("xdg-open", url).start();
            } catch (Exception e2) {
                System.err.println("Haven: could not open " + url + ": " + e2);
            }
        }
    }

    /**
     * The title band: the pack's own title art (the CUS2 desk with the logo and the Haven Remix tape, from FancyMenu's
     * assets), centred on the logo with the tape overhang in view, fading into the window background. Before the
     * first update there is no art yet: a plain dark band with the name.
     */
    static final class Hero extends JComponent {
        static final int CX = 960, CY = 280;    // centre of the CREATE logo inside the 1920x1080 image
        static final int RX = 710, RY = 250;    // half extents that must stay in view: tape end, hammer top, tag bottom
        private final BufferedImage bg;

        Hero(BufferedImage bg) {
            this.bg = bg;
            setPreferredSize(new Dimension(720, bg == null ? 120 : 250));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            Color body = UIManager.getColor("Panel.background");
            if (bg == null) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(0x3A2A16), 0, h, body));
                g.fillRect(0, 0, w, h);
                g.setColor(new Color(0xECECEC));
                g.setFont(getFont().deriveFont(Font.BOLD, 30f));
                g.drawString("Haven", 24, 56);
                g.setColor(Ui.MUTED);
                g.setFont(getFont().deriveFont(Font.PLAIN, 13f));
                g.drawString("Create Ultimate Selection 2 · Haven Remix", 25, 82);
                g.dispose();
                return;
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int pad = 14;
            double fit = Math.min((w / 2.0 - pad) / RX, (h / 2.0 - pad) / RY);
            double cover = Math.max((double) w / bg.getWidth(), (double) h / bg.getHeight());
            double s = Math.max(fit, cover);
            int dw = (int) Math.round(bg.getWidth() * s), dh = (int) Math.round(bg.getHeight() * s);
            int x = (int) Math.round(w / 2.0 - CX * s);
            int y = (int) Math.round(h / 2.0 - CY * s);
            x = Math.max(w - dw, Math.min(0, x));
            y = Math.max(h - dh, Math.min(0, y));
            g.drawImage(bg, x, y, dw, dh, null);
            Color clear = new Color(body.getRed(), body.getGreen(), body.getBlue(), 0);
            g.setPaint(new GradientPaint(0, h - 64, clear, 0, h, body));
            g.fillRect(0, h - 64, w, 64);
            g.dispose();
        }
    }
}
