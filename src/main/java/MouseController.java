import java.awt.*;
import java.awt.event.InputEvent;

public class MouseController {
    private final Robot robot;

    public MouseController() {
        try {
            this.robot = new Robot();
            this.robot.setAutoDelay(5);
        } catch (AWTException e) {
            throw new RuntimeException("Failed to initialize mouse controller", e);
        }
    }

    /**
     * Move to (x,y) and left-click. Coordinates are clamped to the primary screen bounds
     * to avoid Robot throwing exceptions or moving outside visible area.
     */
    public void click(int x, int y) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int cx = Math.max(0, Math.min(x, screen.width - 1));
        int cy = Math.max(0, Math.min(y, screen.height - 1));

        robot.mouseMove(cx, cy);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(50);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
}