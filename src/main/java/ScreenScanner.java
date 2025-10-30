import java.awt.*;
import java.awt.image.BufferedImage;

public class ScreenScanner {
    private final Robot robot;

    public ScreenScanner() {
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            throw new RuntimeException("Failed to initialize screen scanner", e);
        }
    }

    /**
     * Capture the entire primary screen and return the BufferedImage.
     * Caller can pass the screenshot to ImageMatcher.findMatch(...) to avoid
     * multiple captures for the same check/verification.
     */
    public BufferedImage takeScreenshot() {
        Rectangle screenRect = getScreenBounds();
        return robot.createScreenCapture(screenRect);
    }

    /**
     * Capture a specific region (clamped to screen bounds).
     */
    public BufferedImage takeScreenshot(Rectangle region) {
        Rectangle bounds = getScreenBounds();
        Rectangle r = new Rectangle(region);
        // clamp region to screen bounds
        if (r.x < bounds.x) r.x = bounds.x;
        if (r.y < bounds.y) r.y = bounds.y;
        if (r.x + r.width > bounds.x + bounds.width) r.width = Math.max(0, bounds.x + bounds.width - r.x);
        if (r.y + r.height > bounds.y + bounds.height) r.height = Math.max(0, bounds.y + bounds.height - r.y);
        if (r.width <= 0 || r.height <= 0) return null;
        return robot.createScreenCapture(r);
    }

    /**
     * Convenience: capture and search immediately.
     */
    public Rectangle scanAndFind(ImageMatcher matcher) {
        BufferedImage screenshot = takeScreenshot();
        return matcher.findMatch(screenshot);
    }

    private Rectangle getScreenBounds() {
        // prefer the maximum window bounds to avoid taskbar overlap surprises,
        // but fall back to full screen size.
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            bounds = new Rectangle(0, 0, d.width, d.height);
        }
        return bounds;
    }
}