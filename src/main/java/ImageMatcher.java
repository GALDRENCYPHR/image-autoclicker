import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageMatcher {
    private final BufferedImage targetImage;

    public ImageMatcher(String imagePath) {
        try {
            this.targetImage = ImageIO.read(new File(imagePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load target image", e);
        }
    }

    public Rectangle findMatch(BufferedImage screenshot) {
        int w = screenshot.getWidth() - targetImage.getWidth();
        int h = screenshot.getHeight() - targetImage.getHeight();

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (isMatch(screenshot, x, y)) {
                    return new Rectangle(x, y, targetImage.getWidth(), targetImage.getHeight());
                }
            }
        }
        return null;
    }

    private boolean isMatch(BufferedImage screenshot, int startX, int startY) {
        for (int x = 0; x < targetImage.getWidth(); x++) {
            for (int y = 0; y < targetImage.getHeight(); y++) {
                if (targetImage.getRGB(x, y) != screenshot.getRGB(startX + x, startY + y)) {
                    return false;
                }
            }
        }
        return true;
    }
}