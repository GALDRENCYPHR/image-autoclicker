import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;

public class AutoClicker {
    private final ScreenScanner scanner;
    private final MouseController mouse;
    private final ImageMatcher matcher;
    private volatile boolean running = false;
    private Thread workerThread;

    // clickOffsetX/Y allow clicking a particular part of the image (e.g. right side of button)
    private final int clickOffsetX;
    private final int clickOffsetY;
    private final int maxClickRetries = 5;
    private final long afterClickVerifyDelayMs = 300;
    private final long scanIntervalMs;

    /**
     * New constructor that accepts matcher tuning and click offset + scan interval.
     */
    public AutoClicker(String targetImagePath, int tolerance, int stride,
                       int clickOffsetX, int clickOffsetY, long scanIntervalMs) {
        this.scanner = new ScreenScanner();
        this.mouse = new MouseController();
        this.matcher = new ImageMatcher(targetImagePath, tolerance, stride);
        this.clickOffsetX = clickOffsetX;
        this.clickOffsetY = clickOffsetY;
        this.scanIntervalMs = Math.max(100, scanIntervalMs);
    }

    /**
     * Convenience constructor with defaults similar to earlier behavior.
     */
    public AutoClicker(String targetImagePath) {
        this(targetImagePath, 30, 2, 0, 0, 3000);
    }

    /**
     * Starts the scanning/clicking loop in a background thread (non-blocking).
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(() -> {
            while (running) {
                try {
                    Rectangle matchLocation = scanner.scanAndFind(matcher);
                    if (matchLocation != null) {
                        int targetX = matchLocation.x + matchLocation.width / 2 + clickOffsetX;
                        int targetY = matchLocation.y + matchLocation.height / 2 + clickOffsetY;

                        boolean clicked = attemptClickWithVerify(targetX, targetY);
                        if (!clicked) {
                            // optional: could log to UI or file
                        }
                    }
                    Thread.sleep(scanIntervalMs);
                } catch (InterruptedException e) {
                    // interrupted -> stop loop
                    Thread.currentThread().interrupt();
                    stop();
                } catch (Exception ex) {
                    // catch-all to avoid thread death; could be logged
                    ex.printStackTrace();
                }
            }
        }, "AutoClicker-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private boolean attemptClickWithVerify(int x, int y) throws InterruptedException {
        for (int attempt = 0; attempt < maxClickRetries; attempt++) {
            mouse.click(x, y);
            Thread.sleep(afterClickVerifyDelayMs);

            // verify: if the matcher no longer finds the button, success
            Rectangle stillThere = scanner.scanAndFind(matcher);
            if (stillThere == null) return true;

            // else wait a bit and retry
            Thread.sleep(200);
        }
        return false;
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Small Swing control UI to configure offsets and start/stop the clicker.
     */
    private static class ControlUI {
        private final JFrame frame = new JFrame("AutoClicker Control");
        private final JTextField imagePathField = new JTextField(30);
        private final JTextField tolField = new JTextField("30", 5);
        private final JTextField strideField = new JTextField("2", 5);
        private final JTextField offsetXField = new JTextField("0", 5);
        private final JTextField offsetYField = new JTextField("0", 5);
        private final JTextField intervalField = new JTextField("3000", 7);
        private final JButton startBtn = new JButton("Start");
        private final JButton stopBtn = new JButton("Stop");
        private AutoClicker currentClicker = null;

        ControlUI() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.EAST;
            panel.add(new JLabel("Image:"), c);
            c.gridx = 1; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST;
            panel.add(imagePathField, c);
            JButton browse = new JButton("Browse");
            c.gridx = 3; c.gridy = 0; c.gridwidth = 1;
            panel.add(browse, c);

            c.gridx = 0; c.gridy = 1; panel.add(new JLabel("Tolerance:"), c);
            c.gridx = 1; c.gridy = 1; panel.add(tolField, c);
            c.gridx = 2; c.gridy = 1; panel.add(new JLabel("Stride:"), c);
            c.gridx = 3; c.gridy = 1; panel.add(strideField, c);

            c.gridx = 0; c.gridy = 2; panel.add(new JLabel("Click Offset X:"), c);
            c.gridx = 1; c.gridy = 2; panel.add(offsetXField, c);
            c.gridx = 2; c.gridy = 2; panel.add(new JLabel("Click Offset Y:"), c);
            c.gridx = 3; c.gridy = 2; panel.add(offsetYField, c);

            c.gridx = 0; c.gridy = 3; panel.add(new JLabel("Scan interval ms:"), c);
            c.gridx = 1; c.gridy = 3; panel.add(intervalField, c);

            JPanel btnRow = new JPanel();
            btnRow.add(startBtn);
            btnRow.add(stopBtn);
            c.gridx = 0; c.gridy = 4; c.gridwidth = 4;
            panel.add(btnRow, c);

            stopBtn.setEnabled(false);

            browse.addActionListener(e -> onBrowse());
            startBtn.addActionListener(this::onStart);
            stopBtn.addActionListener(this::onStop);

            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(panel, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
        }

        private void onBrowse() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select target image (accept button)");
            int res = chooser.showOpenDialog(frame);
            if (res == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                imagePathField.setText(f.getAbsolutePath());
            }
        }

        private void onStart(ActionEvent ev) {
            String path = imagePathField.getText().trim();
            if (path.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please choose an image file first.", "Missing image", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int tol = parseIntOr(path, tolField.getText().trim(), 30);
            int stride = parseIntOr(path, strideField.getText().trim(), 2);
            int ox = parseIntOr(path, offsetXField.getText().trim(), 0);
            int oy = parseIntOr(path, offsetYField.getText().trim(), 0);
            long interval = parseLongOr(path, intervalField.getText().trim(), 3000);

            try {
                currentClicker = new AutoClicker(path, tol, stride, ox, oy, interval);
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(frame, "Failed to create AutoClicker: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            currentClicker.start();
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            imagePathField.setEnabled(false);
            tolField.setEnabled(false);
            strideField.setEnabled(false);
            offsetXField.setEnabled(false);
            offsetYField.setEnabled(false);
            intervalField.setEnabled(false);
        }

        private void onStop(ActionEvent ev) {
            if (currentClicker != null) {
                currentClicker.stop();
                currentClicker = null;
            }
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            imagePathField.setEnabled(true);
            tolField.setEnabled(true);
            strideField.setEnabled(true);
            offsetXField.setEnabled(true);
            offsetYField.setEnabled(true);
            intervalField.setEnabled(true);
        }

        private int parseIntOr(String ctx, String txt, int def) {
            try { return Integer.parseInt(txt); } catch (Exception e) { return def; }
        }
        private long parseLongOr(String ctx, String txt, long def) {
            try { return Long.parseLong(txt); } catch (Exception e) { return def; }
        }

        void show() {
            frame.setVisible(true);
        }
    }

    /**
     * Main: if args provided, behave headless using first arg as image path.
     * If no args, show the small UI to configure offsets and start/stop.
     */
    public static void main(String[] args) {
        if (args.length > 0) {
            // headless: run with defaults, blocking start
            String imagePath = args[0];
            int tolerance = args.length > 1 ? parseIntArg(args[1], 30) : 30;
            int stride = args.length > 2 ? parseIntArg(args[2], 2) : 2;
            int offsetX = args.length > 3 ? parseIntArg(args[3], 0) : 0;
            int offsetY = args.length > 4 ? parseIntArg(args[4], 0) : 0;
            long interval = args.length > 5 ? parseLongArg(args[5], 3000) : 3000;

            AutoClicker clicker = new AutoClicker(imagePath, tolerance, stride, offsetX, offsetY, interval);
            clicker.start();
            // keep main thread alive while running
            while (clicker.isRunning()) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        } else {
            // show UI
            SwingUtilities.invokeLater(() -> {
                ControlUI ui = new ControlUI();
                // default image location convenience
                ui.imagePathField.setText("e:\\Documents\\Auto click\\image-autoclicker\\accept.png");
                ui.show();
            });
        }
    }

    private static int parseIntArg(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
    private static long parseLongArg(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
}