package com.dubsof.graph.ingest;

/**
 * Where a file is in the pipeline (the {@code files.status} column).
 * Each constant keeps the text stored in the database, so the schema and the API stay unchanged.
 */
public enum FileStatus {
    /** Just ingested; text not read yet. */
    NEW("new"),
    /** Text read (natively or by OCR). */
    OK("ok"),
    /** Readable but contains no text (or a stock photo that is not OCR'd). */
    EMPTY("empty"),
    /** Could not be opened or parsed. */
    CORRUPT("corrupt"),
    /** Ignored on purpose: lock files, code, media, binaries. */
    SKIPPED("skipped"),
    /** Image-only; waiting for an OCR backend. */
    NEEDS_OCR("needs_ocr"),
    /** A zip archive: its members are separate files. */
    CONTAINER("container");

    private final String value;

    FileStatus(String value) {
        this.value = value;
    }

    /** The text stored in the database. */
    public String value() {
        return value;
    }

    public static FileStatus fromValue(String value) {
        for (FileStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown file status: " + value);
    }
}
