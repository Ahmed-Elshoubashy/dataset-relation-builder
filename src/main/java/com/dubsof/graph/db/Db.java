package com.dubsof.graph.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite access: the schema plus a few JDBC helpers so the rest of the code can stay short.
 * Same schema as the Python version, so a graph.db built by either can be served by either.
 */
public final class Db {

    private Db() {
    }

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS files ("
            + " id INTEGER PRIMARY KEY,"
            + " path TEXT UNIQUE NOT NULL,"          // relative to the dataset; '::' separates archive/attachment members
            + " parent_id INTEGER REFERENCES files(id),"
            + " blob_path TEXT NOT NULL,"            // where the bytes live on disk
            + " sha256 TEXT NOT NULL,"
            + " size INTEGER NOT NULL,"
            + " ext TEXT,"
            + " kind TEXT NOT NULL,"                 // sniffed from content: pdf, eml, docx, png, ...
            + " area TEXT,"                          // top-level folder: Customers, Finance, ...
            + " folder_company TEXT,"                // Customers/<company>/...
            + " folder_job TEXT,"                    // .../JOB-YYYY-NNNN Title/...
            + " folder_category TEXT,"               // .../Invoices/...
            + " status TEXT NOT NULL DEFAULT 'new'," // new | ok | empty | corrupt | skipped | needs_ocr | container
            + " text_source TEXT,"                   // native | claude | tesseract
            + " text TEXT,"
            + " error TEXT,"
            + " duplicate_of INTEGER REFERENCES files(id))",
        "CREATE INDEX IF NOT EXISTS files_sha ON files(sha256)",
        "CREATE TABLE IF NOT EXISTS mentions ("
            + " id INTEGER PRIMARY KEY,"
            + " file_id INTEGER NOT NULL REFERENCES files(id),"
            + " etype TEXT NOT NULL,"                // company | person | project | document | product
            + " surface TEXT NOT NULL,"              // text exactly as seen
            + " role TEXT NOT NULL,"                 // where/how it was seen (bill_to, folder, email_from, ...)
            + " attrs TEXT NOT NULL DEFAULT '{}',"
            + " entity_id INTEGER REFERENCES entities(id),"
            + " method TEXT,"                        // how it was resolved
            + " confidence REAL)",
        "CREATE INDEX IF NOT EXISTS mentions_file ON mentions(file_id)",
        "CREATE INDEX IF NOT EXISTS mentions_entity ON mentions(entity_id)",
        "CREATE TABLE IF NOT EXISTS facts ("         // typed links between mentions of one file
            + " id INTEGER PRIMARY KEY,"
            + " file_id INTEGER NOT NULL REFERENCES files(id),"
            + " src INTEGER NOT NULL REFERENCES mentions(id),"
            + " rel TEXT NOT NULL,"
            + " dst INTEGER NOT NULL REFERENCES mentions(id))",
        "CREATE TABLE IF NOT EXISTS entities ("
            + " id INTEGER PRIMARY KEY,"
            + " etype TEXT NOT NULL,"
            + " name TEXT NOT NULL,"
            + " key TEXT NOT NULL,"
            + " attrs TEXT NOT NULL DEFAULT '{}',"
            + " UNIQUE(etype, key))",
        "CREATE TABLE IF NOT EXISTS aliases ("
            + " entity_id INTEGER NOT NULL REFERENCES entities(id),"
            + " alias TEXT NOT NULL,"
            + " method TEXT NOT NULL,"
            + " confidence REAL NOT NULL,"
            + " count INTEGER NOT NULL,"
            + " PRIMARY KEY (entity_id, alias))",
        "CREATE TABLE IF NOT EXISTS relations ("
            + " id INTEGER PRIMARY KEY,"
            + " src INTEGER NOT NULL REFERENCES entities(id),"
            + " dst INTEGER NOT NULL REFERENCES entities(id),"
            + " rel TEXT NOT NULL,"
            + " weight INTEGER NOT NULL DEFAULT 1,"
            + " derived INTEGER NOT NULL DEFAULT 0,"
            + " UNIQUE(src, dst, rel))",
        "CREATE INDEX IF NOT EXISTS relations_dst ON relations(dst)",
        "CREATE TABLE IF NOT EXISTS relation_evidence ("
            + " relation_id INTEGER NOT NULL REFERENCES relations(id),"
            + " file_id INTEGER NOT NULL REFERENCES files(id),"
            + " PRIMARY KEY (relation_id, file_id))",
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",   // dataset / settings of this graph
        "CREATE TABLE IF NOT EXISTS issues ("
            + " id INTEGER PRIMARY KEY,"
            + " kind TEXT NOT NULL,"
            + " severity TEXT NOT NULL,"             // info | warn | error
            + " detail TEXT NOT NULL,"
            + " file_id INTEGER REFERENCES files(id),"
            + " entity_id INTEGER REFERENCES entities(id))",
    };

    /** Opens a database file. Writers get manual commits (much faster for bulk inserts). */
    public static Connection open(File file, boolean autoCommit) throws SQLException {
        file.getAbsoluteFile().getParentFile().mkdirs();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        Statement st = conn.createStatement();
        st.execute("PRAGMA journal_mode=WAL");
        st.execute("PRAGMA foreign_keys=ON");
        st.execute("PRAGMA busy_timeout=5000");
        st.close();
        conn.setAutoCommit(autoCommit);
        return conn;
    }

    public static void init(Connection conn) throws SQLException {
        for (String sql : SCHEMA) {
            update(conn, sql);
        }
        commit(conn);
    }

    /** Drops everything derived from file text, so extraction and resolution can run again. */
    public static void resetGraph(Connection conn) throws SQLException {
        for (String table : new String[] {"relation_evidence", "relations", "aliases", "issues", "facts", "mentions", "entities"}) {
            update(conn, "DELETE FROM " + table);
        }
        commit(conn);
    }

    public static void setMeta(Connection conn, String key, Object value) throws SQLException {
        update(conn, "INSERT OR REPLACE INTO meta VALUES (?,?)", key, String.valueOf(value));
        commit(conn);
    }

    public static Map<String, String> meta(Connection conn) throws SQLException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map<String, Object> r : query(conn, "SELECT key, value FROM meta")) {
            out.put((String) r.get("key"), (String) r.get("value"));
        }
        return out;
    }

    /** Moves a finished database into place. No connection may be open on {@code target}. */
    public static void install(File built, File target) throws IOException {
        new File(target.getPath() + "-wal").delete();
        new File(target.getPath() + "-shm").delete();
        Files.move(built.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ JDBC helpers

    public static List<Map<String, Object>> query(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = prepare(conn, sql, args);
        try {
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } finally {
            ps.close();
        }
    }

    public static Map<String, Object> one(Connection conn, String sql, Object... args) throws SQLException {
        List<Map<String, Object>> rows = query(conn, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static Object scalar(Connection conn, String sql, Object... args) throws SQLException {
        Map<String, Object> row = one(conn, sql, args);
        return row == null ? null : row.values().iterator().next();
    }

    public static long count(Connection conn, String sql, Object... args) throws SQLException {
        Object v = scalar(conn, sql, args);
        return v == null ? 0 : ((Number) v).longValue();
    }

    public static int update(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = prepare(conn, sql, args);
        try {
            return ps.executeUpdate();
        } finally {
            ps.close();
        }
    }

    /** Runs an INSERT and returns the new row id, or 0 when the row was ignored. */
    public static long insert(Connection conn, String sql, Object... args) throws SQLException {
        if (update(conn, sql, args) == 0) {
            return 0;
        }
        return count(conn, "SELECT last_insert_rowid()");
    }

    public static void commit(Connection conn) throws SQLException {
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }

    public static long id(Object value) {
        return ((Number) value).longValue();
    }

    private static PreparedStatement prepare(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }
}
