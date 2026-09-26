package com.dubsof.graph.read;

/**
 * How a file's text was obtained (the {@code files.text_source} column).
 * Each constant keeps the text stored in the database, so the schema and the API stay unchanged.
 */
public enum TextSource {
    /** Parsed directly from the file (PDF text layer, docx, xlsx, e-mail, plain text). */
    NATIVE("native"),
    /** Transcribed by Claude. */
    CLAUDE("claude"),
    /** Transcribed by tesseract. */
    TESSERACT("tesseract");

    private final String value;

    TextSource(String value) {
        this.value = value;
    }

    /** The text stored in the database. */
    public String value() {
        return value;
    }

    /** Null stays null: files that have not been read yet have no source. */
    public static TextSource fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TextSource s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown text source: " + value);
    }
}
