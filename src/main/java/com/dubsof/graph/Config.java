package com.dubsof.graph;

import com.dubsof.graph.resolve.AdjudicatorType;

import java.io.File;

/**
 * Every setting of the app in one place. The ones read from an environment variable (ERKG_..., PORT,
 * ANTHROPIC_API_KEY) can be changed without rebuilding; the others are tuning values, changed here.
 * A dataset's own conventions (folder layout, owner, legal suffixes, ...) are not here: they live in its
 * profile (see dataset.Profile).
 */
public final class Config {

    private Config() {
    }

    // ================================================================ files and folders (environment)

    /** The dataset folder pre-filled in the "Analyse dataset" dialog (ERKG_DATA_ROOT). */
    public static File dataRoot = new File(env("ERKG_DATA_ROOT", "../john-doe")).getAbsoluteFile();

    /** Where the graph, the OCR/Claude cache and extracted archive members are stored (ERKG_WORK_DIR). */
    public static final File WORK_DIR = new File(env("ERKG_WORK_DIR", "data")).getAbsoluteFile();
    /** The graph the explorer serves; each analysis builds graph.db.building and swaps it in. */
    public static final File DB_FILE = new File(WORK_DIR, "graph.db");
    /** OCR text, adjudicator verdicts and general-extractor answers, by file content; survives every rebuild. */
    public static final File OCR_CACHE_FILE = new File(WORK_DIR, "ocr_cache.db");

    /** The profiles offered in the Analyse dialog's Profile menu, *.json (ERKG_PROFILES_DIR). */
    public static final File PROFILES_DIR = new File(env("ERKG_PROFILES_DIR", "profiles")).getAbsoluteFile();

    /** When set (Docker), the dataset picker cannot leave this folder (ERKG_BROWSE_ROOT). */
    public static final File BROWSE_ROOT = System.getenv("ERKG_BROWSE_ROOT") != null
            ? new File(System.getenv("ERKG_BROWSE_ROOT")).getAbsoluteFile() : null;

    // ================================================================ analysis choices (environment)

    /**
     * Optional: the server's default profile (ERKG_PROFILE), used when a dataset has no profile.json of its
     * own and none is chosen in the Analyse dialog (see dataset.Profile).
     */
    public static final String PROFILE = envOrNull("ERKG_PROFILE");

    /**
     * Optional: the organisation that owns the file share (ERKG_OWNER) and its e-mail domain (ERKG_OWNER_DOMAIN).
     * Normally detected from the files; the dialog and a dataset's own profile.json come first.
     */
    public static final String OWNER = envOrNull("ERKG_OWNER");
    public static final String OWNER_DOMAIN = envOrNull("ERKG_OWNER_DOMAIN");

    /** Who decides borderline company matches: "rules" (keep them apart) or "claude" (ERKG_ADJUDICATOR). */
    public static final AdjudicatorType ADJUDICATOR = AdjudicatorType.fromValue(env("ERKG_ADJUDICATOR", "rules"));

    /** Photos carry no text, so they are not sent to OCR; ERKG_OCR_PHOTOS=1 sends every image anyway. */
    public static final boolean OCR_PHOTOS = "1".equals(env("ERKG_OCR_PHOTOS", "0"));

    /** Parallel Claude or Tesseract calls, for OCR and for the general extractor (ERKG_OCR_WORKERS). */
    public static final int OCR_WORKERS = Integer.parseInt(env("ERKG_OCR_WORKERS", "8"));

    // ================================================================ Claude calls

    /** The model for OCR, the general extractor and the adjudicator (ERKG_CLAUDE_MODEL). Part of every cache key. */
    public static final String CLAUDE_MODEL = env("ERKG_CLAUDE_MODEL", "claude-opus-5");
    /** Retries of a failed Claude call (rate limit, overload, network) before the file falls back. */
    public static final int CLAUDE_MAX_RETRIES = 4;
    /** Thinking effort for the structured calls (extraction, adjudication): simple questions, answered fast. */
    public static final String CLAUDE_EFFORT = "low";
    /** Longest answer to an OCR call: a dense page of text. */
    public static final long OCR_MAX_OUTPUT_TOKENS = 16000;
    /** Longest answer from the general extractor: the entities and relations of one file, as JSON. */
    public static final long FREE_TEXT_MAX_OUTPUT_TOKENS = 4000;
    /** Longest text sent to the general extractor; the start of a business document holds the parties and references. */
    public static final int FREE_TEXT_MAX_INPUT_CHARS = 12000;
    /** Longest answer from the adjudicator: one verdict with a reason, as JSON. */
    public static final long ADJUDICATOR_MAX_OUTPUT_TOKENS = 2000;

    // ================================================================ 2 · read

    /** Longest text kept per file; a very long file (a price list, a log) is cut here. */
    public static final int MAX_STORED_TEXT_CHARS = 60000;
    /** Resolution Tesseract reads a PDF page at: sharp enough for small print, fast enough for hundreds of scans. */
    public static final int TESSERACT_DPI = 200;
    /**
     * An image is a screenshot or scan (worth OCR) when at least this share of its pixels equal their right-hand
     * neighbour: screens and scans have large flat areas, photos have camera noise (john-doe: screenshots 96.7%+,
     * photos 93.2% or less).
     */
    public static final double SCREENSHOT_MIN_IDENTICAL_NEIGHBOURS = 0.95;
    /** Rows of an image compared for that test; enough to judge the whole image without reading every row. */
    public static final int SCREENSHOT_ROWS_SAMPLED = 256;

    // ================================================================ owner detection (after read)

    /** A letterhead must be the first line of at least this many PDFs (and 10% of them) to name the owner. */
    public static final int OWNER_MIN_LETTERHEAD_PDFS = 5;
    /** The owner's e-mail domain must be on at least this many e-mails (as sender or recipient)... */
    public static final int OWNER_MIN_EMAILS_WITH_DOMAIN = 5;
    /** ...and on at least this share of the e-mails that have a company (not free-mail) domain. */
    public static final double OWNER_MIN_SHARE_OF_EMAILS = 0.3;
    /** Without a letterhead, an organisation matching the owner's domain must be named in this many files to name it. */
    public static final int OWNER_MIN_FILES_NAMING_IT = 2;

    /** A shipped profile is suggested when its folder patterns match at least this share of the dataset's files. */
    public static final double PROFILE_SUGGEST_MIN_SHARE = 0.3;

    // ================================================================ 3 · extract (confidence of each source)

    /** A company name read from a file name: often cut off ("Redwood Timber &amp; J"). */
    public static final double FILENAME_COMPANY_CONFIDENCE = 0.7;
    /** A product code read from a file name ("GB-40_Datasheet"). */
    public static final double FILENAME_PRODUCT_CONFIDENCE = 0.8;
    /** Anything Claude found in free text (role "llm"): below template fields, above the offline rules. */
    public static final double CLAUDE_FINDING_CONFIDENCE = 0.7;
    /** Anything the offline rules found in free text (role "free_text"). */
    public static final double RULES_FINDING_CONFIDENCE = 0.6;

    // ================================================================ 4 · resolve

    /** A company match at or above this score is the same company, automatically. */
    public static final double COMPANY_MATCH_ACCEPT = 0.80;
    /** Between this and COMPANY_MATCH_ACCEPT the adjudicator decides; below it, the name is a new company. */
    public static final double COMPANY_MATCH_GRAY = 0.65;
    /** Without project folders, a project title must appear in this many files (per customer) to become a project. */
    public static final int TITLE_ONLY_PROJECT_MIN_FILES = 2;
    /** Confidence of a project known only by its title: weaker evidence than a folder or a job id. */
    public static final double TITLE_ONLY_PROJECT_CONFIDENCE = 0.6;

    // ================================================================ 5 · relate

    /** Shortest name the gazetteer searches for in free text; shorter names match inside other words too often. */
    public static final int GAZETTEER_MIN_NAME_LENGTH = 6;
    /** Confidence of a known name found in free text by the gazetteer, lower than any template field. */
    public static final double GAZETTEER_CONFIDENCE = 0.6;

    // ================================================================ server and explorer

    /** HTTP port the explorer listens on (PORT). */
    public static final int PORT = Integer.parseInt(env("PORT", "8765"));
    /** Requests served at once. */
    public static final int HTTP_THREADS = 8;
    /** How long a request waits for SQLite while an analysis writes, in milliseconds, before failing. */
    public static final int SQLITE_BUSY_TIMEOUT_MS = 5000;
    /** Entities per page in the explorer's list, by default and at most. */
    public static final int ENTITY_LIST_DEFAULT_LIMIT = 100;
    public static final int ENTITY_LIST_MAX_LIMIT = 1000;
    /** Nodes in one graph view, by default and at most; and how many hops from the centre it may go. */
    public static final int GRAPH_DEFAULT_NODES = 70;
    public static final int GRAPH_MAX_NODES = 600;
    public static final int GRAPH_MAX_DEPTH = 2;
    /** "Find connection": the longest path searched between two entities, and how many shortest paths are shown. */
    public static final int CONNECTION_MAX_HOPS = 4;
    public static final int CONNECTION_MAX_PATHS = 5;
    /** Evidence files listed in an entity's details panel. */
    public static final int MAX_EVIDENCE_FILES_SHOWN = 400;

    /** ANTHROPIC_API_KEY, or null when it is missing or empty (compose passes an empty value when unset). */
    public static String apiKeyFromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key == null || key.trim().isEmpty() ? null : key.trim();
    }

    private static String envOrNull(String name) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
