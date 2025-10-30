import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class AutoClicker {
    private final ScreenScanner scanner;
    private final MouseController mouse;
    private final ImageMatcher matcher; // may be null when running change-detection-only
    private volatile boolean running = false;
    private Thread workerThread;
    private final Rectangle monitorRegion; // if non-null, scanning limited to this region

    // clickOffsetX/Y allow clicking a particular part of the image (e.g. right side of button)
    private final int clickOffsetX;
    private final int clickOffsetY;
    private final int maxClickRetries = 5;
    private final long afterClickVerifyDelayMs = 300;
    private final long scanIntervalMs;

    // pixel-change detection
    private final boolean detectOnPixelChange;
    private final int changeThresholdPercent;

    public AutoClicker(String targetImagePath, int tolerance, int stride,
                       int clickOffsetX, int clickOffsetY, long scanIntervalMs, Rectangle monitorRegion,
                       boolean detectOnPixelChange, int changeThresholdPercent) {
        this.scanner = new ScreenScanner();
        this.mouse = new MouseController();
        // matcher is optional — allow null/empty path to run change-detection-only mode
        if (targetImagePath != null && !targetImagePath.trim().isEmpty()) {
            this.matcher = new ImageMatcher(targetImagePath, tolerance, stride);
        } else {
            this.matcher = null;
        }
        this.clickOffsetX = clickOffsetX;
        this.clickOffsetY = clickOffsetY;
        this.scanIntervalMs = Math.max(100, scanIntervalMs);
        this.monitorRegion = monitorRegion;
        this.detectOnPixelChange = detectOnPixelChange;
        this.changeThresholdPercent = Math.max(1, Math.min(100, changeThresholdPercent));
    }

    public AutoClicker(String targetImagePath) {
        this(targetImagePath, 30, 2, 0, 0, 3000, null, false, 5);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(() -> {
            BufferedImage prevShot = null;
            while (running) {
                try {
                    BufferedImage shot = (monitorRegion == null) ? scanner.takeScreenshot() : scanner.takeScreenshot(monitorRegion);

                    if (detectOnPixelChange && prevShot != null && shot != null) {
                        int percentChanged = computeChangePercent(prevShot, shot, 4);
                        if (percentChanged >= changeThresholdPercent) {
                            System.out.println("Major change detected: " + percentChanged + "% >= " + changeThresholdPercent + "%");
                            // For change-trigger mode, click at monitor region top-left + offset fields
                            int baseX = (monitorRegion == null) ? 0 : monitorRegion.x;
                            int baseY = (monitorRegion == null) ? 0 : monitorRegion.y;
                            int targetX = baseX + clickOffsetX;
                            int targetY = baseY + clickOffsetY;
                            System.out.println("Change-trigger: clicking at (" + targetX + "," + targetY + ")");
                            attemptClickWithVerify(targetX, targetY);
                            prevShot = shot;
                            Thread.sleep(scanIntervalMs);
                            continue;
                        }
                    }

                    if (matcher != null) {
                        Rectangle matchLocation = matcher.findMatch(shot);
                        if (matchLocation != null) {
                            int baseX = (monitorRegion == null) ? 0 : monitorRegion.x;
                            int baseY = (monitorRegion == null) ? 0 : monitorRegion.y;

                            int targetX = baseX + matchLocation.x + matchLocation.width / 2 + clickOffsetX;
                            int targetY = baseY + matchLocation.y + matchLocation.height / 2 + clickOffsetY;

                            System.out.println("Found match at " + matchLocation + " -> click at (" + targetX + "," + targetY + ")");
                            boolean clicked = attemptClickWithVerify(targetX, targetY);
                            if (!clicked) {
                                System.out.println("Click attempts failed for target at (" + targetX + "," + targetY + ")");
                            }
                        }
                    }

                    prevShot = shot;
                    Thread.sleep(scanIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stop();
                } catch (HeadlessException | RasterFormatException ex) {
                    System.err.println("Error while taking screenshot: " + ex.getMessage());
                }
            }
        }, "AutoClicker-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private int computeChangePercent(BufferedImage a, BufferedImage b, int sampleStep) {
        if (a == null || b == null) return 0;
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        if (w == 0 || h == 0) return 0;

        long total = 0;
        long changed = 0;
        int step = Math.max(1, sampleStep);
        final int perPixelThreshold = 30;
        int thr2 = perPixelThreshold * perPixelThreshold;

        for (int x = 0; x < w; x += step) {
            for (int y = 0; y < h; y += step) {
                int rgb1 = a.getRGB(x, y);
                int rgb2 = b.getRGB(x, y);
                int r1 = (rgb1 >> 16) & 0xFF;
                int g1 = (rgb1 >> 8) & 0xFF;
                int b1 = rgb1 & 0xFF;
                int r2 = (rgb2 >> 16) & 0xFF;
                int g2 = (rgb2 >> 8) & 0xFF;
                int b2 = rgb2 & 0xFF;
                int dr = r1 - r2;
                int dg = g1 - g2;
                int db = b1 - b2;
                int dist2 = dr * dr + dg * dg + db * db;
                if (dist2 > thr2) changed++;
                total++;
            }
        }
        if (total == 0) return 0;
        return (int) ((changed * 100) / total);
    }

    private boolean attemptClickWithVerify(int x, int y) throws InterruptedException {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
            System.out.println("Target click coordinates out of bounds: (" + x + "," + y + ")");
            return false;
        }
        // If we're operating in change-detection-only mode, or there's no matcher available,
        // don't try to verify using ImageMatcher — just perform the clicks and return success.
        if (detectOnPixelChange || matcher == null) {
            // perform one or a few clicks to react to the change and consider it successful
            for (int attempt = 0; attempt < Math.max(1, maxClickRetries); attempt++) {
                mouse.click(x, y);
                Thread.sleep(afterClickVerifyDelayMs);
            }
            return true;
        }

        for (int attempt = 0; attempt < maxClickRetries; attempt++) {
            mouse.click(x, y);
            Thread.sleep(afterClickVerifyDelayMs);

            BufferedImage verifyShot = (monitorRegion == null) ? scanner.takeScreenshot() : scanner.takeScreenshot(monitorRegion);
            Rectangle stillThere = matcher.findMatch(verifyShot);
            if (stillThere == null) {
                System.out.println("Verified: template disappeared after click (attempt " + (attempt + 1) + ")");
                return true;
            } else {
                System.out.println("Template still present after click (attempt " + (attempt + 1) + "), retrying...");
            }

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

    private static class ControlUI {
        private final JFrame frame = new JFrame("AutoClicker Control");
        private final JWindow crosshairOverlay;
        private final JTextField imagePathField = new JTextField(30);
        private final JTextField tolField = new JTextField("30", 5);
        private final JTextField strideField = new JTextField("2", 5);
        private final Timer crosshairTimer;
        private volatile boolean trackingMouse = false;
        private final JTextField offsetXField = new JTextField("0", 5);
        private final JTextField offsetYField = new JTextField("0", 5);
        private final JTextField intervalField = new JTextField("3000", 7);

        private final JTextField monitorXField = new JTextField("0", 6);
        private final JTextField monitorYField = new JTextField("0", 6);
        private final JCheckBox useMonitorCheck = new JCheckBox("Use 500x500 monitor region", false);
        private final JButton useMousePosBtn = new JButton("Use Mouse Pos");
        private final JButton pickRegionBtn = new JButton("Pick Region");

    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton testClickBtn = new JButton("Test Click");
    private final JButton lockPositionBtn = new JButton("Lock Position");
    private final JButton pickClickPosBtn = new JButton("Pick Click Position");
    private boolean pickingClickPos = false;
        private AutoClicker currentClicker = null;

        private final JPanel previewPanel;
        private BufferedImage previewImage = null;
        private final ScreenScanner previewScanner = new ScreenScanner();
        private final Timer previewTimer;

        private final JCheckBox detectChangeCheck = new JCheckBox("Detect on pixel change", false);
        private final JTextField changeThresholdField = new JTextField("5", 5);

        ControlUI() {
            // Create crosshair overlay (always-on-top, transparent window)
            crosshairOverlay = new JWindow();
            crosshairOverlay.setBackground(new Color(0, 0, 0, 0));
            crosshairOverlay.setSize(50, 50);
            JPanel crosshairPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    setBackground(new Color(0, 0, 0, 0));
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(255, 0, 0, 180));
                    g2.setStroke(new BasicStroke(2));
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    g2.drawLine(cx - 10, cy, cx + 10, cy);
                    g2.drawLine(cx, cy - 10, cx, cy + 10);
                    g2.dispose();
                }
            };
            crosshairPanel.setOpaque(false);
            crosshairOverlay.add(crosshairPanel);

            // Timer to update crosshair position
            crosshairTimer = new Timer(16, e -> {
                if (trackingMouse) {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    crosshairOverlay.setLocation(p.x - 25, p.y - 25);
                }
            });

            JPanel controls = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.EAST;
            controls.add(new JLabel("Image:"), c);
            c.gridx = 1; c.gridy = 0; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST;
            controls.add(imagePathField, c);
            JButton browse = new JButton("Browse");
            c.gridx = 3; c.gridy = 0; c.gridwidth = 1;
            controls.add(browse, c);

            c.gridx = 0; c.gridy = 1; controls.add(new JLabel("Tolerance:"), c);
            c.gridx = 1; c.gridy = 1; controls.add(tolField, c);
            c.gridx = 2; c.gridy = 1; controls.add(new JLabel("Stride:"), c);
            c.gridx = 3; c.gridy = 1; controls.add(strideField, c);

            c.gridx = 0; c.gridy = 2; controls.add(new JLabel("Click Offset X:"), c);
            c.gridx = 1; c.gridy = 2; controls.add(offsetXField, c);
            c.gridx = 2; c.gridy = 2; controls.add(new JLabel("Click Offset Y:"), c);
            c.gridx = 3; c.gridy = 2; controls.add(offsetYField, c);

            c.gridx = 0; c.gridy = 3; controls.add(new JLabel("Scan interval ms:"), c);
            c.gridx = 1; c.gridy = 3; controls.add(intervalField, c);

            c.gridx = 0; c.gridy = 4; controls.add(useMonitorCheck, c);
            c.gridx = 1; c.gridy = 4; controls.add(new JLabel("X:"), c);
            c.gridx = 2; c.gridy = 4; controls.add(monitorXField, c);
            c.gridx = 3; c.gridy = 4; controls.add(new JLabel("Y:"), c);
            c.gridx = 4; c.gridy = 4; controls.add(monitorYField, c);

            c.gridx = 1; c.gridy = 5; controls.add(useMousePosBtn, c);
            c.gridx = 2; c.gridy = 5; controls.add(pickRegionBtn, c);

            JPanel btnRow = new JPanel();
            btnRow.add(startBtn);
            btnRow.add(stopBtn);
            btnRow.add(testClickBtn);
            btnRow.add(lockPositionBtn);
            btnRow.add(pickClickPosBtn);
            c.gridx = 0; c.gridy = 6; c.gridwidth = 6;
            controls.add(btnRow, c);

            c.gridx = 0; c.gridy = 7; c.gridwidth = 6;
            controls.add(detectChangeCheck, c);
            c.gridx = 1; c.gridy = 8; controls.add(new JLabel("Change threshold %:"), c);
            c.gridx = 2; c.gridy = 8; controls.add(changeThresholdField, c);

            stopBtn.setEnabled(false);

            browse.addActionListener(e -> onBrowse());
            startBtn.addActionListener(e -> onStart());
            stopBtn.addActionListener(e -> onStop());
            useMousePosBtn.addActionListener(e -> onUseMousePos());
            pickRegionBtn.addActionListener(e -> onPickRegion());
            testClickBtn.addActionListener(e -> onTestClick());
            JButton lockPositionBtn = new JButton("Lock Position");
            lockPositionBtn.addActionListener(e -> {
                if (!trackingMouse) {
                    // Start tracking
                    trackingMouse = true;
                    crosshairOverlay.setVisible(true);
                    crosshairTimer.start();
                    lockPositionBtn.setText("Lock");
                } else {
                    onLockPosition();
                    lockPositionBtn.setText("Lock Position");
                }
            });

            previewPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.setColor(Color.DARK_GRAY);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    if (previewImage != null) {
                        g.drawImage(previewImage, 0, 0, getWidth(), getHeight(), null);
                    } else {
                        g.setColor(Color.LIGHT_GRAY);
                        g.drawString("Preview (500x500)", 10, 20);
                    }
                    // Draw crosshair at the current click offset
                    try {
                        int ox = Integer.parseInt(offsetXField.getText().trim());
                        int oy = Integer.parseInt(offsetYField.getText().trim());
                        double sx = previewImage != null ? (double) previewPanel.getWidth() / previewImage.getWidth() : 1.0;
                        double sy = previewImage != null ? (double) previewPanel.getHeight() / previewImage.getHeight() : 1.0;
                        int px = (int) (ox * sx);
                        int py = (int) (oy * sy);
                        g.setColor(Color.RED);
                        g.drawLine(px - 10, py, px + 10, py);
                        g.drawLine(px, py - 10, px, py + 10);
                    } catch (NumberFormatException ignore) {}
                }
            };
            previewPanel.setPreferredSize(new Dimension(500, 500));
            previewPanel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            previewPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!pickingClickPos) return;
                    if (previewImage == null) return;
                    double sx = (double) previewImage.getWidth() / previewPanel.getWidth();
                    double sy = (double) previewImage.getHeight() / previewPanel.getHeight();
                    int imgX = (int) (e.getX() * sx);
                    int imgY = (int) (e.getY() * sy);
                    offsetXField.setText(String.valueOf(imgX));
                    offsetYField.setText(String.valueOf(imgY));
                    pickingClickPos = false;
                    pickClickPosBtn.setText("Pick Click Position");
                    previewPanel.setCursor(Cursor.getDefaultCursor());
                    previewPanel.repaint();
                }
            });
            previewPanel.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    if (pickingClickPos && previewImage != null) {
                        previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    } else {
                        previewPanel.setCursor(Cursor.getDefaultCursor());
                    }
                }
            });
            pickClickPosBtn.addActionListener(e -> {
                pickingClickPos = !pickingClickPos;
                if (pickingClickPos) {
                    pickClickPosBtn.setText("Click in Preview");
                    previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                } else {
                    pickClickPosBtn.setText("Pick Click Position");
                    previewPanel.setCursor(Cursor.getDefaultCursor());
                }
            });

            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setLayout(new BorderLayout(8, 8));
            frame.getContentPane().add(controls, BorderLayout.CENTER);
            frame.getContentPane().add(previewPanel, BorderLayout.EAST);
            frame.pack();
            frame.setLocationRelativeTo(null);

            previewTimer = new Timer(400, ev -> refreshPreview());
            previewTimer.setRepeats(true);
            previewTimer.start();
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

        private void onLockPosition() {
            // This method is called directly from the lambda
            Point p = MouseInfo.getPointerInfo().getLocation();
            if (useMonitorCheck.isSelected()) {
                // Convert to monitor-relative coordinates
                int mx = parseIntOr(monitorXField.getText().trim(), 0);
                int my = parseIntOr(monitorYField.getText().trim(), 0);
                offsetXField.setText(String.valueOf(p.x - mx));
                offsetYField.setText(String.valueOf(p.y - my));
            } else {
                // Use absolute coordinates
                offsetXField.setText(String.valueOf(p.x));
                offsetYField.setText(String.valueOf(p.y));
            }
            trackingMouse = false;
            crosshairOverlay.setVisible(false);
            crosshairTimer.stop();
            refreshPreview();
        }

        private void onUseMousePos() {
            Point p = MouseInfo.getPointerInfo().getLocation();
            monitorXField.setText(String.valueOf(p.x));
            monitorYField.setText(String.valueOf(p.y));
            refreshPreview();
        }

        private void onPickRegion() {
            Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            final JWindow overlay = new JWindow();
            overlay.setAlwaysOnTop(true);
            overlay.setBackground(new Color(0, 0, 0, 32));
            overlay.setBounds(screenBounds);

            final Point[] start = new Point[1];
            final Rectangle[] sel = new Rectangle[1];

            JPanel draw = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (sel[0] != null) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setColor(new Color(0, 120, 215, 80));
                        g2.fill(sel[0]);
                        g2.setColor(new Color(0, 120, 215, 200));
                        g2.setStroke(new BasicStroke(2));
                        g2.draw(sel[0]);
                        g2.dispose();
                    }
                }
            };
            draw.setOpaque(false);

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    start[0] = e.getPoint();
                    sel[0] = new Rectangle(start[0]);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    Point p = e.getPoint();
                    int x = Math.min(start[0].x, p.x);
                    int y = Math.min(start[0].y, p.y);
                    int w = Math.abs(p.x - start[0].x);
                    int h = Math.abs(p.y - start[0].y);
                    sel[0] = new Rectangle(x, y, w, h);
                    draw.repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (sel[0] != null) {
                        Rectangle s = sel[0];
                        int screenX = screenBounds.x + s.x;
                        int screenY = screenBounds.y + s.y;
                        monitorXField.setText(String.valueOf(screenX));
                        monitorYField.setText(String.valueOf(screenY));
                        useMonitorCheck.setSelected(true);
                        refreshPreview();
                    }
                    overlay.dispose();
                }
            };

            draw.addMouseListener(ma);
            draw.addMouseMotionListener(ma);
            overlay.getContentPane().add(draw);
            overlay.setVisible(true);
        }

        private void refreshPreview() {
            if (!useMonitorCheck.isSelected()) {
                previewImage = null;
                previewPanel.repaint();
                return;
            }
            int mx = parseIntOr(monitorXField.getText().trim(), 0);
            int my = parseIntOr(monitorYField.getText().trim(), 0);
            Rectangle r = new Rectangle(mx, my, 500, 500);
            BufferedImage shot = previewScanner.takeScreenshot(r);
            if (shot != null) previewImage = shot;
            previewPanel.repaint();
        }

        private void onStart() {
            String path = imagePathField.getText().trim();
            // image path is optional; leave empty for change-detection-only mode
            if (path.isEmpty()) path = null;
            int tol = parseIntOr(tolField.getText().trim(), 30);
            int stride = parseIntOr(strideField.getText().trim(), 2);
            int ox = parseIntOr(offsetXField.getText().trim(), 0);
            int oy = parseIntOr(offsetYField.getText().trim(), 0);
            long interval = parseLongOr(intervalField.getText().trim(), 3000);

            Rectangle monitor = null;
            if (useMonitorCheck.isSelected()) {
                int mx = parseIntOr(monitorXField.getText().trim(), 0);
                int my = parseIntOr(monitorYField.getText().trim(), 0);
                monitor = new Rectangle(mx, my, 500, 500);
            }

            boolean detectOnPixelChange = detectChangeCheck.isSelected();
            int changeThresholdPercent = parseIntOr(changeThresholdField.getText().trim(), 5);

            try {
                currentClicker = new AutoClicker(path, tol, stride, ox, oy, interval, monitor, detectOnPixelChange, changeThresholdPercent);
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
            useMonitorCheck.setEnabled(false);
            monitorXField.setEnabled(false);
            monitorYField.setEnabled(false);
            useMousePosBtn.setEnabled(false);
            detectChangeCheck.setEnabled(false);
            changeThresholdField.setEnabled(false);
        }

        private void onStop() {
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
            useMonitorCheck.setEnabled(true);
            monitorXField.setEnabled(true);
            monitorYField.setEnabled(true);
            useMousePosBtn.setEnabled(true);
            detectChangeCheck.setEnabled(true);
            changeThresholdField.setEnabled(true);
        }

        private void onTestClick() {
            try {
                int ox = parseIntOr(offsetXField.getText().trim(), 0);
                int oy = parseIntOr(offsetYField.getText().trim(), 0);
                Rectangle monitor = null;
                if (useMonitorCheck.isSelected()) {
                    int mx = parseIntOr(monitorXField.getText().trim(), 0);
                    int my = parseIntOr(monitorYField.getText().trim(), 0);
                    monitor = new Rectangle(mx, my, 500, 500);
                }

                int baseX = (monitor == null) ? 0 : monitor.x;
                int baseY = (monitor == null) ? 0 : monitor.y;
                int targetX = baseX + ox;
                int targetY = baseY + oy;

                int resp = JOptionPane.showConfirmDialog(frame,
                        "Will click at (" + targetX + "," + targetY + "). Proceed?",
                        "Test Click", JOptionPane.YES_NO_OPTION);
                if (resp == JOptionPane.YES_OPTION) {
                    MouseController testMouse = new MouseController();
                    testMouse.click(targetX, targetY);
                    JOptionPane.showMessageDialog(frame, "Test click performed.", "Done", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (HeadlessException e) {
                JOptionPane.showMessageDialog(frame, "Test click failed: Device has no mouse or display", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private int parseIntOr(String txt, int def) {
            try { return Integer.parseInt(txt); }
            catch (NumberFormatException e) { return def; }
        }
        private long parseLongOr(String txt, long def) {
            try { return Long.parseLong(txt); }
            catch (NumberFormatException e) { return def; }
        }

        void show() {
            frame.setVisible(true);
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            String imagePath = args[0];
            int tolerance = args.length > 1 ? parseIntArg(args[1], 30) : 30;
            int stride = args.length > 2 ? parseIntArg(args[2], 2) : 2;
            int offsetX = args.length > 3 ? parseIntArg(args[3], 0) : 0;
            int offsetY = args.length > 4 ? parseIntArg(args[4], 0) : 0;
            long interval = args.length > 5 ? parseLongArg(args[5], 3000) : 3000;

            AutoClicker clicker = new AutoClicker(imagePath, tolerance, stride, offsetX, offsetY, interval, null, false, 5);
            clicker.start();
            while (clicker.isRunning()) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        } else {
            SwingUtilities.invokeLater(() -> {
                ControlUI ui = new ControlUI();
                ui.imagePathField.setText("e:\\Documents\\Auto click\\image-autoclicker\\accept.png");
                ui.show();
            });
        }
    }

    private static int parseIntArg(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
    private static long parseLongArg(String s, long def) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }
}