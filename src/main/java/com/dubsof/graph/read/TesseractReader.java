package com.dubsof.graph.read;

import com.dubsof.graph.util.Text;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Local OCR through the tesseract command-line tool (brew install tesseract).
 * PDF pages are rendered to PNG with PDFBox first.
 */
public class TesseractReader implements TextReader {

    private static final int DPI = 200;

    public TesseractReader() {
        if (!isInstalled()) {
            throw new ReaderUnavailableException("tesseract binary not found on PATH");
        }
    }

    public static boolean isInstalled() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start();
            Text.readAll(p.getInputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String name() {
        return "tesseract";
    }

    public String read(byte[] data, String kind, String filename) throws Exception {
        if (!kind.equals("pdf")) {
            File img = File.createTempFile("ocr", "." + kind);
            try {
                Files.write(img.toPath(), data);
                return ocr(img);
            } finally {
                img.delete();
            }
        }
        StringBuilder out = new StringBuilder();
        PDDocument doc = Loader.loadPDF(data);
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, DPI);
                File img = File.createTempFile("ocr", ".png");
                try {
                    ImageIO.write(page, "png", img);
                    out.append(ocr(img)).append('\n');
                } finally {
                    img.delete();
                }
            }
        } finally {
            doc.close();
        }
        return out.toString().trim();
    }

    /** --psm 4: treat the page as a single column of variable-sized text. */
    private static String ocr(File image) throws Exception {
        Process p = new ProcessBuilder("tesseract", image.getAbsolutePath(), "stdout", "--psm", "4").start();
        String text = new String(Text.readAll(p.getInputStream()), StandardCharsets.UTF_8);
        Text.readAll(p.getErrorStream());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("tesseract failed on " + image.getName());
        }
        return text.trim();
    }
}
