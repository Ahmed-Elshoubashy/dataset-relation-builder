package com.dubsof.graph.read;

import com.dubsof.graph.Config;
import com.dubsof.graph.ingest.FileKind;
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

    public OcrBackend backend() {
        return OcrBackend.TESSERACT;
    }

    public String read(byte[] data, FileKind kind, String filename) throws Exception {
        if (kind != FileKind.PDF) {
            File img = File.createTempFile("ocr", "." + kind.value());
            try {
                Files.write(img.toPath(), data);
                return ocr(img);
            } finally {
                img.delete();
            }
        }
        StringBuilder out = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, Config.TESSERACT_DPI);
                File img = File.createTempFile("ocr", ".png");
                try {
                    ImageIO.write(page, "png", img);
                    out.append(ocr(img)).append('\n');
                } finally {
                    img.delete();
                }
            }
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
