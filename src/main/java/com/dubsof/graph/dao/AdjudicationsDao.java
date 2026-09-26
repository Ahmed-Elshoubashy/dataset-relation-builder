package com.dubsof.graph.dao;

import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** All SQL for the {@code adjudications} table in ocr_cache.db: Claude's cached verdicts on borderline matches. */
public class AdjudicationsDao {

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS adjudications (k TEXT PRIMARY KEY, v TEXT)";
    private static final String SELECT_VERDICT = "SELECT v FROM adjudications WHERE k = ?";
    private static final String SAVE = "INSERT OR REPLACE INTO adjudications (k, v) VALUES (?, ?)";

    public void createTable(Connection conn) throws SQLException {
        Db.update(conn, CREATE_TABLE);
    }

    /** The cached verdict JSON for a question key, or null. */
    public String findVerdict(Connection conn, String key) throws SQLException {
        return Db.first(conn, SELECT_VERDICT, AdjudicationsDao::mapVerdict, key);
    }

    public void save(Connection conn, String key, String verdictJson) throws SQLException {
        Db.update(conn, SAVE, key, verdictJson);
    }

    private static String mapVerdict(ResultSet rs) throws SQLException {
        return rs.getString("v");
    }
}
