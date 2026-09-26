package com.dubsof.graph.read;

import com.dubsof.graph.ingest.FileKind;

/**
 * The OCR seam: anything that turns an image-only document into plain text.
 *
 * Implementations return text the extractors can parse: one visual line per output line,
 * "Label: value" pairs kept on one line, table rows as cells joined with " | ".
 * Pick a backend with ERKG_OCR (claude | tesseract | none) or from the web UI.
 */
public interface TextReader {

    /** Which backend this is; its value is stored with each transcription. */
    OcrBackend backend();

    /** Text of an image-only file. {@code kind} is PDF, PNG or JPG (see {@link FileKind#isOcrable()}). */
    String read(byte[] data, FileKind kind, String filename) throws Exception;
}
