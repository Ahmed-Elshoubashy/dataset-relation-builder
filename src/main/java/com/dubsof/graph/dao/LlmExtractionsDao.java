package com.dubsof.graph.dao;

import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * All SQL for the {@code llm_extractions} table in ocr_cache.db: Claude's answers for the general
 * extractor, by file content, model and prompt version, so rebuilding a graph never pays for the same
 * file twice, and a changed prompt never reuses answers to the old one.
 */
public class LlmExtractionsDao {

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS llm_extractions"
            + " (sha256 TEXT, model TEXT, prompt_version INTEGER, json TEXT, PRIMARY KEY (sha256, model, prompt_version))";
    private static final String COLUMNS = "SELECT name FROM pragma_table_info('llm_extractions')";
    private static final String DROP_TABLE = "DROP TABLE llm_extractions";
    private static final String SELECT_JSON = "SELECT json FROM llm_extractions WHERE sha256 = ? AND model = ? AND prompt_version = ?";
    private static final String SAVE = "INSERT OR REPLACE INTO llm_extractions (sha256, model, prompt_version, json) VALUES (?, ?, ?, ?)";

    /**
     * Creates the table. A table from before prompt versions existed is dropped first: its answers
     * came from an unknown prompt, so none of them can be reused.
     */
    public void createTable(Connection conn) throws SQLException {
        List<String> columns = Db.list(conn, COLUMNS, rs -> rs.getString(1));
        if (!columns.isEmpty() && !columns.contains("prompt_version")) {
            Db.update(conn, DROP_TABLE);
        }
        Db.update(conn, CREATE_TABLE);
    }

    /** The cached answer for a file's content, or null. */
    public String findJson(Connection conn, String sha256, String model, int promptVersion) throws SQLException {
        return Db.first(conn, SELECT_JSON, LlmExtractionsDao::mapJson, sha256, model, promptVersion);
    }

    public void save(Connection conn, String sha256, String model, int promptVersion, String json) throws SQLException {
        Db.update(conn, SAVE, sha256, model, promptVersion, json);
    }

    private static String mapJson(ResultSet rs) throws SQLException {
        return rs.getString("json");
    }
}
