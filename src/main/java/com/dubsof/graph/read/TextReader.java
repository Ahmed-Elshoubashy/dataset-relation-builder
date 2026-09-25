package com.dubsof.graph.read;

/**
 * The OCR seam: anything that turns an image-only document into plain text.
 *
 * Implementations return text the extractors can parse: one visual line per output line,
 * "Label: value" pairs kept on one line, table rows as cells joined with " | ".
 * Pick a backend with ERKG_OCR (claude | tesseract | none) or from the web UI.
 */
public interface TextReader {

    /** Short name, stored with each transcription ("claude", "tesseract", "none"). */
    String name();

    /** Text of an image-only file. {@code kind} is "pdf", "png" or "jpg". */
    String read(byte[] data, String kind, String filename) throws Exception;
}
