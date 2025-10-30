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
        if (screenshot == null) return null;
        int maxX = screenshot.getWidth() - targetImage.getWidth();
        int maxY = screenshot.getHeight() - targetImage.getHeight();
        if (maxX < 0 || maxY < 0) return null; // template larger than screenshot

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

        // quick early-check: sample a few pixels first (skip fully transparent template pixels)
        int samplesX = Math.max(1, tw / 4);
        int samplesY = Math.max(1, th / 4);
        for (int sx = 0; sx < tw; sx += samplesX) {
            for (int sy = 0; sy < th; sy += samplesY) {
                int trgb = targetImage.getRGB(sx, sy);
                int alpha = (trgb >> 24) & 0xFF;
                if (alpha == 0) continue; // ignore transparent parts of template
                int sRgb = screen.getRGB(startX + sx, startY + sy);
                if (!pixelsClose(trgb, sRgb)) {
                    return false;
                }
            }
        }

        // full check (skip transparent template pixels)
        for (int x = 0; x < tw; x++) {
            for (int y = 0; y < th; y++) {
                int trgb = targetImage.getRGB(x, y);
                int alpha = (trgb >> 24) & 0xFF;
                if (alpha == 0) continue;
                int sRgb = screen.getRGB(startX + x, startY + y);
                if (!pixelsClose(trgb, sRgb)) {
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