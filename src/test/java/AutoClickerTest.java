import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AutoClickerTest {
    @Test
    void testImageMatcher() {
        ImageMatcher matcher = new ImageMatcher("test/resources/sample.png");
        assertNotNull(matcher);
    }

    @Test
    void testMouseController() {
        MouseController controller = new MouseController();
        assertNotNull(controller);
    }
}