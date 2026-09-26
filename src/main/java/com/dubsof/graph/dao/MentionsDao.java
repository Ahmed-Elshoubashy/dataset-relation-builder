package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.MentionRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** All SQL for the {@code mentions} table. */
public class MentionsDao {

    private static final String INSERT =
            "INSERT INTO mentions (file_id, etype, surface, role, attrs, confidence) VALUES (?, ?, ?, ?, '{}', ?)";
    private static final String INSERT_RESOLVED =
            "INSERT INTO mentions (file_id, etype, surface, role, attrs, entity_id, method, confidence)"
                    + " VALUES (?, ?, ?, ?, '{}', ?, ?, ?)";
    private static final String UPDATE_ATTRS = "UPDATE mentions SET attrs = ? WHERE id = ?";
    private static final String UPDATE_RESOLUTION = "UPDATE mentions SET entity_id = ?, method = ?, confidence = ? WHERE id = ?";
    private static final String UNLINK_ENTITY = "UPDATE mentions SET entity_id = NULL, method = ? WHERE entity_id = ?";
    private static final String SELECT_ALL = "SELECT * FROM mentions ORDER BY id";
    private static final String SELECT_RESOLVED = "SELECT * FROM mentions WHERE entity_id IS NOT NULL ORDER BY id";
    private static final String SELECT_RESOLVED_WITH_ROLE =
            "SELECT * FROM mentions WHERE role = ? AND entity_id IS NOT NULL ORDER BY id";
    private static final String COUNT_RESOLVED = "SELECT COUNT(*) FROM mentions WHERE entity_id IS NOT NULL";
    private static final String COUNTS_BY_METHOD =
            "SELECT etype || ':' || method, COUNT(*) FROM mentions WHERE method IS NOT NULL GROUP BY 1";

    /** Adds an unresolved mention (attributes are set afterwards, once all ids of the file are known). */
    public long insert(Connection conn, long fileId, EntityType etype, String surface, String role, double confidence)
            throws SQLException {
        return Db.insert(conn, INSERT, fileId, etype.value(), surface, role, confidence);
    }

    /** Adds a mention that is already linked to its entity. */
    public long insertResolved(Connection conn, long fileId, EntityType etype, String surface, String role,
                               long entityId, String method, double confidence) throws SQLException {
        return Db.insert(conn, INSERT_RESOLVED, fileId, etype.value(), surface, role, entityId, method, confidence);
    }

    public void updateAttrs(Connection conn, long id, Map<String, Object> attrs) throws SQLException {
        Db.update(conn, UPDATE_ATTRS, Json.write(attrs), id);
    }

    /** Records which entity a mention refers to, the rule that decided it, and how sure it is. */
    public void updateResolution(Connection conn, MentionRow m) throws SQLException {
        Db.update(conn, UPDATE_RESOLUTION, m.entityId, m.method, m.confidence, m.id);
    }

    /** Detaches every mention of an entity that is being removed, marking them with {@code method}. */
    public void unlinkEntity(Connection conn, long entityId, String method) throws SQLException {
        Db.update(conn, UNLINK_ENTITY, method, entityId);
    }

    public List<MentionRow> findAll(Connection conn) throws SQLException {
        return Db.list(conn, SELECT_ALL, MentionsDao::map);
    }

    public List<MentionRow> findResolved(Connection conn) throws SQLException {
        return Db.list(conn, SELECT_RESOLVED, MentionsDao::map);
    }

    public List<MentionRow> findResolvedWithRole(Connection conn, String role) throws SQLException {
        return Db.list(conn, SELECT_RESOLVED_WITH_ROLE, MentionsDao::map, role);
    }

    public long countResolved(Connection conn) throws SQLException {
        return Db.number(conn, COUNT_RESOLVED);
    }

    /** "etype:method" -> number of mentions resolved that way. */
    public Map<String, Long> countsByMethod(Connection conn) throws SQLException {
        return Db.counts(conn, COUNTS_BY_METHOD);
    }

    private static MentionRow map(ResultSet rs) throws SQLException {
        MentionRow m = new MentionRow();
        m.id = rs.getLong("id");
        m.fileId = rs.getLong("file_id");
        m.etype = EntityType.fromValue(rs.getString("etype"));
        m.surface = rs.getString("surface");
        m.role = rs.getString("role");
        m.attrs = Json.readMap(rs.getString("attrs"));
        m.entityId = Db.longOrNull(rs, "entity_id");
        m.method = rs.getString("method");
        m.confidence = Db.doubleOrNull(rs, "confidence");
        return m;
    }
}
