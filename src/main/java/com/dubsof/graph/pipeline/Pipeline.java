package com.dubsof.graph.pipeline;

import com.dubsof.graph.Config;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.Extractor;
import com.dubsof.graph.ingest.Ingestor;
import com.dubsof.graph.read.TextStage;
import com.dubsof.graph.relate.Relator;
import com.dubsof.graph.resolve.Resolver;

import java.io.File;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Runs the five stages: ingest -> read -> extract -> resolve -> relate.
 * Used by the CLI (Main) and by the "Analyse dataset" button (api.AnalysisJob).
 */
public final class Pipeline {

    private Pipeline() {
    }

    /** What build() produced. */
    public static class Result {
        public File built;
        public Map<String, Long> entities;
        public Map<String, Integer> read;
    }

    /**
     * Full analysis of {@code dataRoot} into a fresh database next to {@code target}
     * ("graph.db.building"). The caller moves it into place with Db.install() once no
     * connection is open on {@code target}, so a running server keeps serving the previous
     * graph until the new one is complete.
     *
     * @param apiKey only used by the Claude OCR backend; never stored
     */
    public static Result build(File dataRoot, File target, String ocr, String apiKey, Progress progress) throws Exception {
        dataRoot = dataRoot.getCanonicalFile();
        File built = new File(target.getPath() + ".building");
        built.delete();
        new File(built.getPath() + "-wal").delete();
        new File(built.getPath() + "-shm").delete();
        Connection conn = Db.open(built, false);
        try {
            Db.init(conn);
            Db.setMeta(conn, "data_root", dataRoot.getPath());
            Db.setMeta(conn, "ocr_backend", ocr);
            Db.setMeta(conn, "started_at", now());

            progress.update(1, "ingest", "Scanning " + dataRoot);
            int n = Ingestor.run(conn, dataRoot, progress);
            progress.update(1, "ingest", String.format("%,d files and archive members found", n));

            progress.update(2, "read", "Extracting text");
            Map<String, Integer> read = TextStage.run(conn, ocr, apiKey, progress);
            progress.update(2, "read", String.format("%,d read natively, %,d via OCR, %,d image-only left unread",
                    read.get("native"), read.get("ocr"), read.get("ocr_pending")));
            detectOwner(conn, progress);

            Result result = new Result();
            result.entities = graphStages(conn, progress);
            result.read = read;
            result.built = built;
            Db.setMeta(conn, "finished_at", now());
            Db.query(conn, "PRAGMA wal_checkpoint(TRUNCATE)");   // returns a status row
            return result;
        } finally {
            conn.close();
        }
    }

    /** Stages 3-5 on files that were already read (also used by the "rebuild" command). */
    public static Map<String, Long> graphStages(Connection conn, Progress progress) throws Exception {
        Db.resetGraph(conn);
        long t = System.currentTimeMillis();
        progress.update(3, "extract", "Running");
        progress.update(3, "extract", "Done: " + Extractor.run(conn) + " in " + seconds(t));
        t = System.currentTimeMillis();
        progress.update(4, "resolve", "Running");
        progress.update(4, "resolve", "Done: " + Resolver.run(conn) + " in " + seconds(t));
        t = System.currentTimeMillis();
        progress.update(5, "relate", "Running");
        progress.update(5, "relate", "Done: " + Relator.run(conn) + " in " + seconds(t));
        Map<String, Long> entities = new LinkedHashMap<String, Long>();
        for (Map<String, Object> r : Db.query(conn, "SELECT etype, COUNT(*) n FROM entities GROUP BY 1")) {
            entities.put((String) r.get("etype"), Db.id(r.get("n")));
        }
        return entities;
    }

    /**
     * Sets the owner organisation from the files' text (letterheads, sender domains).
     * Not stored: rebuild/ocr detect it again from the text already in the database.
     */
    public static void detectOwner(Connection conn, Progress progress) throws Exception {
        String[] detected = OwnerDetector.detect(conn);
        if (detected[0] != null) {
            Config.ownerName = detected[0];
        } else if (detected[1] != null) {
            Config.ownerName = detected[1];
        }
        if (detected[1] != null) {
            Config.ownerDomain = detected[1];
        }
        progress.update(2, "read", "Owner organisation: " + Config.ownerName);
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
