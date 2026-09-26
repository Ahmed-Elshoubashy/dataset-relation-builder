package com.dubsof.graph.read;

import com.dubsof.graph.Config;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.util.Text;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Caches transcriptions by (sha256, backend) in a separate database,
 * so rebuilding the graph never pays for the same OCR twice.
 */
public class CachedReader implements TextReader {

    private final TextReader inner;
    private final Connection cache;

    public CachedReader(TextReader inner) throws SQLException {
        this.inner = inner;
        this.cache = Db.open(Config.OCR_CACHE_FILE, true);
        Db.update(cache, "CREATE TABLE IF NOT EXISTS ocr (sha256 TEXT, backend TEXT, text TEXT, PRIMARY KEY (sha256, backend))");
    }

    public String name() {
        return inner.name();
    }

    public String read(byte[] data, FileKind kind, String filename) throws Exception {
        return readWithHash(Text.sha256(data), data, kind, filename);
    }

    public String readWithHash(String sha, byte[] data, FileKind kind, String filename) throws Exception {
        String hit = cached(sha);
        if (hit != null) {
            return hit;
        }
        String text = inner.read(data, kind, filename);
        synchronized (cache) {
            Db.update(cache, "INSERT OR REPLACE INTO ocr VALUES (?,?,?)", sha, inner.name(), text);
        }
        return text;
    }

    private String cached(String sha) throws SQLException {
        synchronized (cache) {
            return (String) Db.scalar(cache, "SELECT text FROM ocr WHERE sha256=? AND backend=?", sha, inner.name());
        }
    }

    public void close() throws SQLException {
        cache.close();
    }
}
