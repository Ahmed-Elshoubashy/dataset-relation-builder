package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.IssueRow;
import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** All SQL for the {@code issues} table (data-quality findings). */
public class IssuesDao {

    private static final String INSERT = "INSERT INTO issues (kind, severity, detail, file_id, entity_id) VALUES (?, ?, ?, ?, ?)";
    private static final String DELETE_BY_ENTITY = "DELETE FROM issues WHERE entity_id = ?";
    private static final String SELECT_BY_FILE = "SELECT * FROM issues WHERE file_id = ? ORDER BY id";
    private static final String COUNTS_BY_KIND = "SELECT kind, COUNT(*) FROM issues GROUP BY kind";

    /** Records a finding about a file and/or an entity (either may be null). */
    public void insert(Connection conn, String kind, String severity, String detail, Long fileId, Long entityId)
            throws SQLException {
        Db.update(conn, INSERT, kind, severity, detail, fileId, entityId);
    }

    public void deleteByEntity(Connection conn, long entityId) throws SQLException {
        Db.update(conn, DELETE_BY_ENTITY, entityId);
    }

    public List<IssueRow> findByFile(Connection conn, long fileId) throws SQLException {
        return Db.list(conn, SELECT_BY_FILE, IssuesDao::map, fileId);
    }

    /** finding kind -> number of findings. */
    public Map<String, Long> countsByKind(Connection conn) throws SQLException {
        return Db.counts(conn, COUNTS_BY_KIND);
    }

    /** Maps a row of {@code issues}; also used by queries that join file paths and entity names. */
    static IssueRow map(ResultSet rs) throws SQLException {
        IssueRow i = new IssueRow();
        i.id = rs.getLong("id");
        i.kind = rs.getString("kind");
        i.severity = rs.getString("severity");
        i.detail = rs.getString("detail");
        i.fileId = Db.longOrNull(rs, "file_id");
        i.entityId = Db.longOrNull(rs, "entity_id");
        return i;
    }
}
