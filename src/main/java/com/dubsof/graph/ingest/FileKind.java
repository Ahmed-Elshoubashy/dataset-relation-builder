package com.dubsof.graph.ingest;

/**
 * The real type of a file, detected from its bytes by {@link Ingestor#sniff} (the {@code files.kind} column).
 * Each constant keeps the text stored in the database, so the schema and the API stay unchanged.
 */
public enum FileKind {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ZIP("zip", "application/zip"),
    PNG("png", "image/png"),
    JPG("jpg", "image/jpeg"),
    RTF("rtf", "text/plain; charset=utf-8"),
    EML("eml", "text/plain; charset=utf-8"),
    VCF("vcf", "text/plain; charset=utf-8"),
    ICS("ics", "text/plain; charset=utf-8"),
    TEXT("text", "text/plain; charset=utf-8"),
    MEDIA("media", "application/octet-stream"),
    CODE("code", "text/plain; charset=utf-8"),
    BINARY("binary", "application/octet-stream"),
    /** Looks like a zip-based file but cannot be opened. */
    CORRUPT("corrupt", "application/octet-stream");

    private final String value;
    private final String contentType;

    FileKind(String value, String contentType) {
        this.value = value;
        this.contentType = contentType;
    }

    /** The text stored in the database. */
    public String value() {
        return value;
    }

    /** HTTP Content-Type used when the explorer opens the original file. */
    public String contentType() {
        return contentType;
    }

    /** Kinds that an OCR backend can read: scanned PDFs and images. */
    public boolean isOcrable() {
        return this == PDF || this == PNG || this == JPG;
    }

    /** Kinds never read at all (no documents in them). */
    public boolean isIgnored() {
        return this == CODE || this == MEDIA || this == BINARY;
    }

    public static FileKind fromValue(String value) {
        for (FileKind k : values()) {
            if (k.value.equals(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown file kind: " + value);
    }
}
