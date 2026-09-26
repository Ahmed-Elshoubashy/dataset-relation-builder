package com.dubsof.graph;

import com.dubsof.graph.resolve.AdjudicatorType;

import java.io.File;

/** Runtime configuration. Every value can be overridden with an environment variable. */
public final class Config {

    private Config() {
    }

    /** Dataset analysed when none is given. */
    public static File dataRoot = new File(env("ERKG_DATA_ROOT", "../john-doe")).getAbsoluteFile();

    /** Where the graph, OCR cache and extracted archive members are stored. */
    public static final File WORK_DIR = new File(env("ERKG_WORK_DIR", "data")).getAbsoluteFile();
    public static final File DB_FILE = new File(WORK_DIR, "graph.db");
    public static final File OCR_CACHE_FILE = new File(WORK_DIR, "ocr_cache.db");
    public static final File BLOB_DIR = new File(WORK_DIR, "blobs");

    public static final int OCR_WORKERS = Integer.parseInt(env("ERKG_OCR_WORKERS", "8"));
    public static final String CLAUDE_MODEL = env("ERKG_CLAUDE_MODEL", "claude-opus-5");

    /** Stock photos (IMG_*.jpg, MKT_*.jpg) carry no text; skip them unless asked. */
    public static final boolean OCR_PHOTOS = "1".equals(env("ERKG_OCR_PHOTOS", "0"));

    /** Who decides borderline company matches: "rules" or "claude". */
    public static final AdjudicatorType ADJUDICATOR = AdjudicatorType.fromValue(env("ERKG_ADJUDICATOR", "rules"));

    /** The organisation that owns the file share. Replaced by auto-detection during an analysis. */
    public static String ownerName = env("ERKG_OWNER", "Meridian Packaging Systems Ltd");
    // TODO check where the domain is used
    public static String ownerDomain = env("ERKG_OWNER_DOMAIN", "meridianpackaging.co.uk");

    /** When set (Docker), the dataset picker cannot leave this folder. */
    public static final File BROWSE_ROOT = System.getenv("ERKG_BROWSE_ROOT") != null
            ? new File(System.getenv("ERKG_BROWSE_ROOT")).getAbsoluteFile() : null;

    public static final int PORT = Integer.parseInt(env("PORT", "8765"));
    public static final int MAX_TEXT_CHARS = 60000;

    /** ANTHROPIC_API_KEY, or null when it is missing or empty (compose passes an empty value when unset). */
    public static String apiKeyFromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key == null || key.trim().isEmpty() ? null : key.trim();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
