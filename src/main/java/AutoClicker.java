import javax.swing.*;
import java.awt.*;

public class AutoClicker {
    private final ScreenScanner scanner;
    private final MouseController mouse;
    private final ImageMatcher matcher;
    private volatile boolean running = false;

    public AutoClicker(String targetImagePath) {
        this.scanner = new ScreenScanner();
        this.mouse = new MouseController();
        this.matcher = new ImageMatcher(targetImagePath);
    }

    public void start() {
        running = true;
        while (running) {
            try {
                Rectangle matchLocation = scanner.scanAndFind(matcher);
                if (matchLocation != null) {
                    mouse.click(matchLocation.x + matchLocation.width/2, 
                              matchLocation.y + matchLocation.height/2);
                }
                Thread.sleep(3000); // Wait 3 seconds between scans
            } catch (InterruptedException e) {
                stop();
            }
        }
    }

    public void stop() {
        running = false;
    }

    public static void main(String[] args) {
        // Update path to point to your accept button image
        String imagePath = "e:\\Documents\\Auto click\\image-autoclicker\\accept.png";
        AutoClicker clicker = new AutoClicker(imagePath);
        clicker.start();
    }
}