package com.dubsof.graph.pipeline;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.MetaDao;
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.dataset.Profile;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.Extractor;
import com.dubsof.graph.ingest.Ingestor;
import com.dubsof.graph.read.OcrBackend;
import com.dubsof.graph.read.TextStage;
import com.dubsof.graph.relate.Relator;
import com.dubsof.graph.resolve.Resolver;

import java.io.File;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

/**
 * Runs the five stages: ingest -> read -> extract -> resolve -> relate.
 * Used by the "Analyse dataset" button (api.AnalysisApi).
 */
public final class Pipeline {

    private static final MetaDao metaDao = new MetaDao();
    private static final EntitiesDao entitiesDao = new EntitiesDao();

    private Pipeline() {
    }

    /** How to run an analysis. */
    public static class Options {
        /** Who reads image-only files. */
        public OcrBackend ocr = OcrBackend.NONE;
        /** Anthropic key for Claude; null means "use ANTHROPIC_API_KEY". Never stored. */
        public String apiKey;
        /** The owner organisation, given by the user; null means "detect it from the files". */
        public String owner;
        /** Let Claude read the files no template recognises (billed); otherwise rules read them, offline. */
        public boolean llmExtraction;
        /** The profile chosen in the Analyse dialog (a shipped profile's file name, or "none"); null: not chosen. */
        public String profile;
        /** The server's owner (ERKG_OWNER) and its domain (ERKG_OWNER_DOMAIN); tests set their own. */
        public String serverOwner = Config.OWNER;
        public String serverOwnerDomain = Config.OWNER_DOMAIN;
    }

    /** What build() produced. */
    public static class Result {
        public File built;
        public Map<String, Long> entities;
        public Map<String, Integer> read;
        /** The owner used for this graph (detected or given). */
        public Owner owner;
        /** Where the dataset's profile came from: a file path, or "defaults". */
        public String profile;
    }

    /**
     * Full analysis of {@code dataRoot} into a fresh database next to {@code target}
     * ("graph.db.building"). The caller moves it into place with Db.install() once no
     * connection is open on {@code target}, so a running server keeps serving the previous
     * graph until the new one is complete. Zip members and e-mail attachments are stored in a
     * "blobs" folder next to {@code target}.
     */
    public static Result build(File dataRoot, File target, Options options, Progress progress) throws Exception {
        dataRoot = dataRoot.getCanonicalFile();
        File built = new File(target.getPath() + ".building");
        built.delete();
        new File(built.getPath() + "-wal").delete();
        new File(built.getPath() + "-shm").delete();
        try (Connection conn = Db.open(built, false)) {
            Db.init(conn);
            metaDao.set(conn, "data_root", dataRoot.getPath());
            metaDao.set(conn, "ocr_backend", options.ocr.value());
            metaDao.set(conn, "started_at", now());

            Profile profile = Profile.forDataset(dataRoot, options.profile);
            progress.update(1, "ingest", "Profile: " + profile.source);
            metaDao.set(conn, "profile", profile.source);
            progress.update(1, "ingest", "Scanning " + dataRoot);
            File blobDir = new File(target.getAbsoluteFile().getParentFile(), "blobs");
            int n = Ingestor.run(conn, dataRoot, blobDir, profile, progress);
            progress.update(1, "ingest", String.format("%,d files and archive members found", n));

            progress.update(2, "read", "Extracting text");
            Map<String, Integer> read = TextStage.run(conn, options.ocr, options.apiKey, progress);
            progress.update(2, "read", String.format("%,d read natively, %,d via OCR, %,d image-only left unread",
                    read.get("native"), read.get("ocr"), read.get("ocr_pending")));
            Owner owner = owner(conn, options, profile);
            progress.update(2, "read", owner.isKnown() ? "Owner organisation: " + owner + (owner.reason == null ? "" : ", " + owner.reason)
                    : "No owner organisation" + (owner.reason == null ? "" : ": " + owner.reason));
            Dataset dataset = new Dataset(owner, profile);

            Result result = new Result();
            result.owner = owner;
            result.profile = profile.source;
            String llmKey = !options.llmExtraction ? null : options.apiKey != null ? options.apiKey : Config.apiKeyFromEnv();
            result.entities = graphStages(conn, dataset, llmKey, progress);
            result.read = read;
            result.built = built;
            metaDao.set(conn, "finished_at", now());
            Db.checkpoint(conn);
            return result;
        }
    }

    /**
     * Stages 3-5 on files that were already read.
     *
     * @param llmKey the key the general extractor uses to ask Claude about unrecognised files; null: rules, offline
     */
    public static Map<String, Long> graphStages(Connection conn, Dataset dataset, String llmKey, Progress progress) throws Exception {
        Db.resetGraph(conn);
        long t = System.currentTimeMillis();
        progress.update(3, "extract", llmKey != null ? "Running (Claude reads the files no template recognises)" : "Running");
        progress.update(3, "extract", "Done: " + Extractor.run(conn, dataset, llmKey, progress) + " in " + seconds(t));
        t = System.currentTimeMillis();
        
        progress.update(4, "resolve", "Running");
        progress.update(4, "resolve", "Done: " + Resolver.run(conn, dataset) + " in " + seconds(t));
        t = System.currentTimeMillis();
        
        progress.update(5, "relate", "Running");
        progress.update(5, "relate", "Done: " + Relator.run(conn, dataset) + " in " + seconds(t));
        
        return entitiesDao.countsByType(conn);
    }

    /** The owner organisation (see {@link #chooseOwner}), detected from the files unless it is given. */
    private static Owner owner(Connection conn, Options options, Profile profile) throws Exception {
        return chooseOwner(options, profile, OwnerDetector.detect(conn, profile.names));
    }

    /**
     * The owner organisation, first found wins:
     * <ol>
     *   <li>the one typed in the Analyse dialog;</li>
     *   <li>{@code owner} in the dataset's own profile.json;</li>
     *   <li>ERKG_OWNER, the server's;</li>
     *   <li>{@code owner} in a chosen, ERKG_PROFILE or suggested profile;</li>
     *   <li>the one detected from the files (see OwnerDetector), or none.</li>
     * </ol>
     * A given name keeps a domain only when it goes with that name: ERKG_OWNER_DOMAIN with ERKG_OWNER, the
     * profile's {@code ownerDomain} with its owner, else the detected domain if it matches the name. So an owner
     * typed in the dialog never gets the domain of another organisation that happened to be detected.
     */
    static Owner chooseOwner(Options options, Profile profile, Owner detected) {
        String dialogOwner = options.owner == null || options.owner.trim().isEmpty() ? null : options.owner.trim();
        if (dialogOwner != null) {
            return given(dialogOwner, null, detected, profile, "from the Analyse dialog");
        }
        if (profile.owner != null && profile.fromDataset) {
            return given(profile.owner, profile.ownerDomain, detected, profile, "from the dataset's profile.json");
        }
        if (options.serverOwner != null) {
            return given(options.serverOwner, options.serverOwnerDomain, detected, profile, "from ERKG_OWNER");
        }
        if (profile.owner != null) {
            return given(profile.owner, profile.ownerDomain, detected, profile, "from the profile");
        }
        return detected;
    }

    /** A given owner: its own domain, else the detected one if it matches the name, else none. */
    private static Owner given(String name, String domain, Owner detected, Profile profile, String reason) {
        if (domain == null && detected.domain != null && profile.names.matchDomain(detected.domain, name) != null) {
            domain = detected.domain;
        }
        return new Owner(name, domain, reason);
    }

    private static String seconds(long start) {
        return String.format("%.1fs", (System.currentTimeMillis() - start) / 1000.0);
    }

    static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
