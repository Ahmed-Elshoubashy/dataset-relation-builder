package com.dubsof.graph.read;

/**
 * Which TextReader handles image-only files (ERKG_OCR, --ocr, or the web UI choice).
 * Each constant keeps the text used on the command line, in the API and in the database.
 */
public enum OcrBackend {
    /** Claude vision API. */
    CLAUDE("claude"),
    /** Local tesseract command. */
    TESSERACT("tesseract"),
    /** No OCR: image-only files stay unread. */
    NONE("none");

    private final String value;

    OcrBackend(String value) {
        this.value = value;
    }

    /** The text used on the command line, in the API and in the database. */
    public String value() {
        return value;
    }

    /** What files read by this backend get as {@code files.text_source}; null for NONE (it reads nothing). */
    public TextSource textSource() {
        switch (this) {
            case CLAUDE:
                return TextSource.CLAUDE;
            case TESSERACT:
                return TextSource.TESSERACT;
            default:
                return null;
        }
    }

    public static OcrBackend fromValue(String value) {
        for (OcrBackend b : values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }
        throw new IllegalArgumentException("unknown OCR backend: " + value);
    }
}
