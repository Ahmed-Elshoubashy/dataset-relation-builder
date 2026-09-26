package com.dubsof.graph.dao;

import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All SQL for the {@code meta} table: which dataset and settings produced this graph. */
public class MetaDao {

    private static final String SET = "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)";
    private static final String SELECT_ALL = "SELECT key, value FROM meta";

    /** Stores one setting and commits it right away. */
    public void set(Connection conn, String key, Object value) throws SQLException {
        Db.update(conn, SET, key, String.valueOf(value));
        Db.commit(conn);
    }

    /** key -> value. */
    public Map<String, String> findAll(Connection conn) throws SQLException {
        List<String[]> rows = Db.list(conn, SELECT_ALL, MetaDao::map);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String[] r : rows) {
            out.put(r[0], r[1]);
        }
        return out;
    }

    private static String[] map(ResultSet rs) throws SQLException {
        return new String[] {rs.getString("key"), rs.getString("value")};
    }
}
