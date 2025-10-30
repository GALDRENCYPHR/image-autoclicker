import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageMatcher {
    private final BufferedImage targetImage;
    private final int tolerance; // color distance tolerance (0 = exact)
    private final int stride;    // sample stride for faster scanning (1 = every pixel)

    public ImageMatcher(String imagePath) {
        this(imagePath, 0, 1);
    }

    public ImageMatcher(String imagePath, int tolerance, int stride) {
        try {
            this.targetImage = ImageIO.read(new File(imagePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load target image", e);
        }
        this.tolerance = Math.max(0, tolerance);
        this.stride = Math.max(1, stride);
    }

    public Rectangle findMatch(BufferedImage screenshot) {
        int maxX = screenshot.getWidth() - targetImage.getWidth();
        int maxY = screenshot.getHeight() - targetImage.getHeight();

        for (int x = 0; x <= maxX; x += stride) {
            for (int y = 0; y <= maxY; y += stride) {
                if (isMatch(screenshot, x, y)) {
                    return new Rectangle(x, y, targetImage.getWidth(), targetImage.getHeight());
                }
            }
        }
        return null;
    }

    private boolean isMatch(BufferedImage screen, int startX, int startY) {
        int tw = targetImage.getWidth();
        int th = targetImage.getHeight();

        // quick early-check: sample a few pixels first
        int samplesX = Math.max(1, tw / 4);
        int samplesY = Math.max(1, th / 4);
        for (int sx = 0; sx < tw; sx += samplesX) {
            for (int sy = 0; sy < th; sy += samplesY) {
                if (!pixelsClose(targetImage.getRGB(sx, sy), screen.getRGB(startX + sx, startY + sy))) {
                    return false;
                }
            }
        }

        // full check
        for (int x = 0; x < tw; x++) {
            for (int y = 0; y < th; y++) {
                if (!pixelsClose(targetImage.getRGB(x, y), screen.getRGB(startX + x, startY + y))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean pixelsClose(int rgb1, int rgb2) {
        if (tolerance == 0) return rgb1 == rgb2;
        int r1 = (rgb1 >> 16) & 0xFF;
        int g1 = (rgb1 >> 8) & 0xFF;
        int b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        int dist2 = dr*dr + dg*dg + db*db;
        return dist2 <= tolerance * tolerance;
    }
}