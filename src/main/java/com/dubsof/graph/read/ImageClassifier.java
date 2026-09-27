package com.dubsof.graph.read;

import com.dubsof.graph.Config;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Tells a screenshot or scan (text worth reading with OCR) from a photo (no text), by looking at the pixels.
 *
 * A screenshot is drawn by a computer: large areas of one exact colour, so almost every pixel is identical
 * to the pixel on its right. A photo has camera noise and gradients, so even a plain-looking photo has far
 * fewer identical neighbours. Measured in the john-doe dataset: every screenshot 96.7% or more, every photo
 * 93.2% or less (most below 70%).
 */
public final class ImageClassifier {


    private ImageClassifier() {
    }

    /**
     * True for a screenshot or scan, false for a photo. An image Java cannot decode counts as a screenshot,
     * so the OCR backend still gets a chance to read it.
     */
    public static boolean looksLikeScreenshot(byte[] data) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            return true;
        }
        if (image == null) {
            return true;
        }
        return identicalNeighbourShare(image) >= Config.SCREENSHOT_MIN_IDENTICAL_NEIGHBOURS;
    }

    /** Share (0..1) of pixels whose colour is exactly the same as the pixel to their right, on sampled rows. */
    static double identicalNeighbourShare(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStep = Math.max(1, height / Config.SCREENSHOT_ROWS_SAMPLED);
        long identical = 0;
        long compared = 0;
        for (int y = 0; y < height; y += rowStep) {
            for (int x = 0; x + 1 < width; x++) {
                if (image.getRGB(x, y) == image.getRGB(x + 1, y)) {
                    identical++;
                }
                compared++;
            }
        }
        return compared == 0 ? 1.0 : (double) identical / compared;
    }
}
