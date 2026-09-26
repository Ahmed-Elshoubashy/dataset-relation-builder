package com.dubsof.graph.dao;

import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** All SQL for the {@code ocr} table in ocr_cache.db: transcriptions by (content hash, backend). */
public class OcrCacheDao {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS ocr (sha256 TEXT, backend TEXT, text TEXT, PRIMARY KEY (sha256, backend))";
    private static final String SELECT_TEXT = "SELECT text FROM ocr WHERE sha256 = ? AND backend = ?";
    private static final String SAVE = "INSERT OR REPLACE INTO ocr (sha256, backend, text) VALUES (?, ?, ?)";

    public void createTable(Connection conn) throws SQLException {
        Db.update(conn, CREATE_TABLE);
    }

    /** The cached text, or null when this backend has not read this content yet. */
    public String findText(Connection conn, String sha256, String backend) throws SQLException {
        return Db.first(conn, SELECT_TEXT, OcrCacheDao::mapText, sha256, backend);
    }

    public void save(Connection conn, String sha256, String backend, String text) throws SQLException {
        Db.update(conn, SAVE, sha256, backend, text);
    }

    private static String mapText(ResultSet rs) throws SQLException {
        return rs.getString("text");
    }
}
