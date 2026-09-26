package com.dubsof.graph.read;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.OcrCacheDao;
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
    private final OcrCacheDao ocrCacheDao = new OcrCacheDao();

    public CachedReader(TextReader inner) throws SQLException {
        this.inner = inner;
        this.cache = Db.open(Config.OCR_CACHE_FILE, true);
        ocrCacheDao.createTable(cache);
    }

    public OcrBackend backend() {
        return inner.backend();
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
            ocrCacheDao.save(cache, sha, inner.backend().value(), text);
        }
        return text;
    }

    private String cached(String sha) throws SQLException {
        synchronized (cache) {
            return ocrCacheDao.findText(cache, sha, inner.backend().value());
        }
    }

    public void close() throws SQLException {
        cache.close();
    }
}
