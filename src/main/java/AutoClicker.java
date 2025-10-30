import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.awt.image.BufferedImage;

public class AutoClicker {
    private final ScreenScanner scanner;
    private final MouseController mouse;
    private final ImageMatcher matcher;
    private volatile boolean running = false;
    private Thread workerThread;
    private final Rectangle monitorRegion; // if non-null, scanning limited to this region

    // clickOffsetX/Y allow clicking a particular part of the image (e.g. right side of button)
    private final int clickOffsetX;
    private final int clickOffsetY;
    private final int maxClickRetries = 5;
    private final long afterClickVerifyDelayMs = 300;
    private final long scanIntervalMs;

    /**
     * New constructor that accepts matcher tuning and click offset + scan interval + optional monitor region.
     */
    public AutoClicker(String targetImagePath, int tolerance, int stride,
                       int clickOffsetX, int clickOffsetY, long scanIntervalMs, Rectangle monitorRegion) {
        this.scanner = new ScreenScanner();
        this.mouse = new MouseController();
        this.matcher = new ImageMatcher(targetImagePath, tolerance, stride);
        this.clickOffsetX = clickOffsetX;
        this.clickOffsetY = clickOffsetY;
        this.scanIntervalMs = Math.max(100, scanIntervalMs);
        this.monitorRegion = monitorRegion;
    }

    /**
     * Convenience constructor with defaults (no monitor region).
     */
    public AutoClicker(String targetImagePath) {
        this(targetImagePath, 30, 2, 0, 0, 3000, null);
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
                    BufferedImage shot = (monitorRegion == null) ? scanner.takeScreenshot() : scanner.takeScreenshot(monitorRegion);
                    Rectangle matchLocation = matcher.findMatch(shot);
                    if (matchLocation != null) {
                        // matchLocation coordinates are relative to the screenshot we passed in.
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
                    Thread.sleep(scanIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stop();
                } catch (Exception ex) {
                    // keep thread alive, print for diagnostics
                    ex.printStackTrace();
                }
            }
        }, "AutoClicker-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private boolean attemptClickWithVerify(int x, int y) throws InterruptedException {
        // Before clicking ensure the point is in primary screen bounds
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
            System.out.println("Target click coordinates out of bounds: (" + x + "," + y + ")");
            return false;
        }

        for (int attempt = 0; attempt < maxClickRetries; attempt++) {
            mouse.click(x, y);
            Thread.sleep(afterClickVerifyDelayMs);

            // verify using a fresh screenshot (clamped to monitor region if used)
            BufferedImage verifyShot = (monitorRegion == null) ? scanner.takeScreenshot() : scanner.takeScreenshot(monitorRegion);
            Rectangle stillThere = matcher.findMatch(verifyShot);
            if (stillThere == null) {
                System.out.println("Verified: template disappeared after click (attempt " + (attempt+1) + ")");
                return true;
            } else {
                System.out.println("Template still present after click (attempt " + (attempt+1) + "), retrying...");
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

    /**
     * Small Swing control UI to configure offsets and start/stop the clicker.
     * Adds a 500x500 preview panel for a monitor region and controls to position that region.
     */
    private static class ControlUI {
        private final JFrame frame = new JFrame("AutoClicker Control");
        private final JTextField imagePathField = new JTextField(30);
        private final JTextField tolField = new JTextField("30", 5);
        private final JTextField strideField = new JTextField("2", 5);
        private final JTextField offsetXField = new JTextField("0", 5);
        private final JTextField offsetYField = new JTextField("0", 5);
        private final JTextField intervalField = new JTextField("3000", 7);

        // monitor region fields (top-left of 500x500 square)
        private final JTextField monitorXField = new JTextField("0", 6);
        private final JTextField monitorYField = new JTextField("0", 6);
        private final JCheckBox useMonitorCheck = new JCheckBox("Use 500x500 monitor region", false);
        private final JButton useMousePosBtn = new JButton("Use Mouse Pos");
        // new button to interactively pick region on screen
        private final JButton pickRegionBtn = new JButton("Pick Region");

        private final JButton startBtn = new JButton("Start");
        private final JButton stopBtn = new JButton("Stop");
        private AutoClicker currentClicker = null;

        // preview
        private final JPanel previewPanel;
        private BufferedImage previewImage = null;
        private final ScreenScanner previewScanner = new ScreenScanner();
        private final Timer previewTimer;

        ControlUI() {
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

            // monitor region controls
            c.gridx = 0; c.gridy = 4; controls.add(useMonitorCheck, c);
            c.gridx = 1; c.gridy = 4; controls.add(new JLabel("X:"), c);
            c.gridx = 2; c.gridy = 4; controls.add(monitorXField, c);
            c.gridx = 3; c.gridy = 4; controls.add(new JLabel("Y:"), c);
            c.gridx = 4; c.gridy = 4; controls.add(monitorYField, c);

            c.gridx = 1; c.gridy = 5; controls.add(useMousePosBtn, c);
            c.gridx = 2; c.gridy = 5; controls.add(pickRegionBtn, c); // add pick button to layout

            JPanel btnRow = new JPanel();
            btnRow.add(startBtn);
            btnRow.add(stopBtn);
            c.gridx = 0; c.gridy = 6; c.gridwidth = 6;
            controls.add(btnRow, c);

            stopBtn.setEnabled(false);

            browse.addActionListener(e -> onBrowse());
            startBtn.addActionListener(this::onStart);
            stopBtn.addActionListener(this::onStop);
            useMousePosBtn.addActionListener(e -> onUseMousePos());
            pickRegionBtn.addActionListener(e -> onPickRegion()); // wire up pick action

            // preview panel on the right (fixed 500x500)
            previewPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.setColor(Color.DARK_GRAY);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    if (previewImage != null) {
                        // draw the screenshot scaled to panel size
                        g.drawImage(previewImage, 0, 0, getWidth(), getHeight(), null);
                    } else {
                        g.setColor(Color.LIGHT_GRAY);
                        g.drawString("Preview (500x500)", 10, 20);
                    }
                    // draw red crosshair at center
                    g.setColor(Color.RED);
                    g.drawLine(getWidth()/2 - 10, getHeight()/2, getWidth()/2 + 10, getHeight()/2);
                    g.drawLine(getWidth()/2, getHeight()/2 - 10, getWidth()/2, getHeight()/2 + 10);
                }
            };
            previewPanel.setPreferredSize(new Dimension(500, 500));
            previewPanel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            previewPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (previewImage == null) return;
                    // calculate clicked point in preview image coords
                    double sx = (double) previewImage.getWidth() / previewPanel.getWidth();
                    double sy = (double) previewImage.getHeight() / previewPanel.getHeight();
                    int imgX = (int) (e.getX() * sx);
                    int imgY = (int) (e.getY() * sy);
                    // set offset fields to the clicked position relative to top-left of monitor region
                    offsetXField.setText(String.valueOf(imgX));
                    offsetYField.setText(String.valueOf(imgY));
                }
            });

            // create frame layout
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().setLayout(new BorderLayout(8,8));
            frame.getContentPane().add(controls, BorderLayout.CENTER);
            frame.getContentPane().add(previewPanel, BorderLayout.EAST);
            frame.pack();
            frame.setLocationRelativeTo(null);

            // preview timer updates every 400ms
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

        private void onUseMousePos() {
            Point p = MouseInfo.getPointerInfo().getLocation();
            monitorXField.setText(String.valueOf(p.x));
            monitorYField.setText(String.valueOf(p.y));
            refreshPreview();
        }

        /**
         * Show a full-screen transparent overlay allowing the user to drag a rectangle.
         * On release sets monitorXField/monitorYField to the rectangle's top-left and enables monitor usage.
         */
        private void onPickRegion() {
            // run on EDT; create overlay JWindow
            Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            final JWindow overlay = new JWindow();
            overlay.setAlwaysOnTop(true);
            overlay.setBackground(new Color(0,0,0,32));
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
                        // translate selection to screen coordinates
                        Rectangle s = sel[0];
                        int screenX = screenBounds.x + s.x;
                        int screenY = screenBounds.y + s.y;
                        // set top-left (we keep fixed 500x500 monitoring, so just set top-left)
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
            int mx = parseIntOr("monitorX", monitorXField.getText().trim(), 0);
            int my = parseIntOr("monitorY", monitorYField.getText().trim(), 0);
            Rectangle r = new Rectangle(mx, my, 500, 500);
            BufferedImage shot = previewScanner.takeScreenshot(r);
            if (shot != null) previewImage = shot;
            previewPanel.repaint();
        }

        private void onStart(ActionEvent ev) {
            String path = imagePathField.getText().trim();
            if (path.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please choose an image file first.", "Missing image", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int tol = parseIntOr("tol", tolField.getText().trim(), 30);
            int stride = parseIntOr("stride", strideField.getText().trim(), 2);
            int ox = parseIntOr("ox", offsetXField.getText().trim(), 0);
            int oy = parseIntOr("oy", offsetYField.getText().trim(), 0);
            long interval = parseLongOr("interval", intervalField.getText().trim(), 3000);

            Rectangle monitor = null;
            if (useMonitorCheck.isSelected()) {
                int mx = parseIntOr("monitorX", monitorXField.getText().trim(), 0);
                int my = parseIntOr("monitorY", monitorYField.getText().trim(), 0);
                monitor = new Rectangle(mx, my, 500, 500);
            }

            try {
                currentClicker = new AutoClicker(path, tol, stride, ox, oy, interval, monitor);
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
            useMonitorCheck.setEnabled(true);
            monitorXField.setEnabled(true);
            monitorYField.setEnabled(true);
            useMousePosBtn.setEnabled(true);
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

            // headless does not support monitor region via args in this version
            AutoClicker clicker = new AutoClicker(imagePath, tolerance, stride, offsetX, offsetY, interval, null);
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