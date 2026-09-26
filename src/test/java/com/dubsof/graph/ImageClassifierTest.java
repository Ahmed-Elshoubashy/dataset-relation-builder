package com.dubsof.graph;

import com.dubsof.graph.read.ImageClassifier;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageClassifierTest {

    /**
     * A dashboard laid out like the john-doe "site_photo.png" attachments (1366x854): flat background,
     * a menu bar, and a table of 14 rows in four columns.
     */
    @Test
    void screenshotIsRead() throws Exception {
        BufferedImage image = new BufferedImage(1366, 854, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xEBEBEF));
        g.fillRect(0, 0, 1366, 854);
        g.setColor(new Color(0x2B2F36));
        g.fillRect(0, 0, 1366, 42);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int row = 0; row < 14; row++) {
            int y = 145 + row * 29;
            g.drawString("JOB-2023-05" + row, 21, y);
            g.drawString("Kingsley Textiles Ltd", 214, y);
            g.drawString("Awaiting Parts", 560, y);
            g.drawString("£35,235", 800, y);
        }
        g.dispose();
        assertTrue(ImageClassifier.looksLikeScreenshot(encode(image, "png")));
    }

    /** A colour photo: every pixel slightly different. */
    @Test
    void noisyPhotoIsSkipped() throws Exception {
        Random random = new Random(1);
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 800; x++) {
                int base = (x + y) / 6;
                image.setRGB(x, y, new Color(clamp(40 + base + random.nextInt(20)), clamp(90 + random.nextInt(20)),
                        clamp(120 + base / 2 + random.nextInt(20))).getRGB());
            }
        }
        assertFalse(ImageClassifier.looksLikeScreenshot(encode(image, "jpg")));
    }

    /** A greyscale photo with a large plain background: few colours, but still camera noise everywhere. */
    @Test
    void plainGreyscalePhotoIsSkipped() throws Exception {
        Random random = new Random(2);
        BufferedImage image = new BufferedImage(960, 480, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 480; y++) {
            for (int x = 0; x < 960; x++) {
                boolean subject = Math.hypot(x - 480, y - 480) < 200;
                int grey = clamp((subject ? 60 : 200) + random.nextInt(6) - 3);
                image.setRGB(x, y, new Color(grey, grey, grey).getRGB());
            }
        }
        assertFalse(ImageClassifier.looksLikeScreenshot(encode(image, "jpg")));
    }

    /** Bytes Java cannot decode: let the OCR backend try. */
    @Test
    void unreadableImageIsRead() {
        assertTrue(ImageClassifier.looksLikeScreenshot(new byte[] {1, 2, 3}));
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
