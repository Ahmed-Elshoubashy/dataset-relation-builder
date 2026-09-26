package com.dubsof.graph.db;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    /** Everything derived from file text, in an order that respects the foreign keys. */
    private static final String[] RESET_GRAPH = {
        "DELETE FROM relation_evidence",
        "DELETE FROM relations",
        "DELETE FROM aliases",
        "DELETE FROM issues",
        "DELETE FROM facts",
        "DELETE FROM mentions",
        "DELETE FROM entities",
    };
    private static final String CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)";
    private static final String LAST_INSERT_ID = "SELECT last_insert_rowid()";

    /** Drops everything derived from file text, so extraction and resolution can run again. */
    public static void resetGraph(Connection conn) throws SQLException {
        for (String sql : RESET_GRAPH) {
            update(conn, sql);
        }
        commit(conn);
    }

    /** Writes pending WAL changes into the main file, so the database is a single file again. */
    public static void checkpoint(Connection conn) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(CHECKPOINT);
        try {
            ps.executeQuery().close();   // returns a status row
        } finally {
            ps.close();
        }
    }

    /** Moves a finished database into place. No connection may be open on {@code target}. */
    public static void install(File built, File target) throws IOException {
        new File(target.getPath() + "-wal").delete();
        new File(target.getPath() + "-shm").delete();
        Files.move(built.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ JDBC helpers (used by the DAOs)

    /** Turns the current row of a ResultSet into an object. */
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Runs a SELECT and maps every row. */
    public static <T> List<T> list(Connection conn, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        PreparedStatement ps = prepare(conn, sql, args);
        try {
            ResultSet rs = ps.executeQuery();
            List<T> rows = new ArrayList<T>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        } finally {
            ps.close();
        }
    }

    /** Runs a SELECT and maps the first row, or returns null when there is none. */
    public static <T> T first(Connection conn, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        List<T> rows = list(conn, sql, mapper, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** First column of the first row as a number (0 when there is no row or it is NULL). */
    public static long number(Connection conn, String sql, Object... args) throws SQLException {
        Long v = first(conn, sql, new RowMapper<Long>() {
            public Long map(ResultSet rs) throws SQLException {
                return longOrNull(rs, 1);
            }
        }, args);
        return v == null ? 0 : v;
    }

    /** A "SELECT key, COUNT(*) … GROUP BY key" query as an ordered map. */
    public static Map<String, Long> counts(Connection conn, String sql, Object... args) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        PreparedStatement ps = prepare(conn, sql, args);
        try {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
            return out;
        } finally {
            ps.close();
        }
    }

    /** A nullable INTEGER column (ResultSet.getLong turns NULL into 0). */
    public static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** A nullable REAL column. */
    public static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
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
        return number(conn, LAST_INSERT_ID);
    }

    public static void commit(Connection conn) throws SQLException {
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }

    private static PreparedStatement prepare(Connection conn, String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }
}
