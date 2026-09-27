package com.dubsof.graph.dao;

import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * All SQL for the {@code llm_extractions} table in ocr_cache.db: Claude's answers for the general
 * extractor, by file content and model, so rebuilding a graph never pays for the same file twice.
 */
public class LlmExtractionsDao {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS llm_extractions (sha256 TEXT, model TEXT, json TEXT, PRIMARY KEY (sha256, model))";
    private static final String SELECT_JSON = "SELECT json FROM llm_extractions WHERE sha256 = ? AND model = ?";
    private static final String SAVE = "INSERT OR REPLACE INTO llm_extractions (sha256, model, json) VALUES (?, ?, ?)";

    public void createTable(Connection conn) throws SQLException {
        Db.update(conn, CREATE_TABLE);
    }

    /** The cached answer for a file's content, or null. */
    public String findJson(Connection conn, String sha256, String model) throws SQLException {
        return Db.first(conn, SELECT_JSON, LlmExtractionsDao::mapJson, sha256, model);
    }

    public void save(Connection conn, String sha256, String model, String json) throws SQLException {
        Db.update(conn, SAVE, sha256, model, json);
    }

    private static String mapJson(ResultSet rs) throws SQLException {
        return rs.getString("json");
    }
}
