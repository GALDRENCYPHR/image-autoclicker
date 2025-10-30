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

    public Rectangle scanAndFind(ImageMatcher matcher) {
        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage screenshot = robot.createScreenCapture(screenRect);
        return matcher.findMatch(screenshot);
    }
}