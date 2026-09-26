package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** All SQL for the {@code entities} table. */
public class EntitiesDao {

    private static final String INSERT = "INSERT INTO entities (etype, name, key, attrs) VALUES (?, ?, ?, ?)";
    private static final String SELECT_BY_ID = "SELECT * FROM entities WHERE id = ?";
    private static final String SELECT_BY_TYPE_AND_KEY = "SELECT * FROM entities WHERE etype = ? AND key = ?";
    private static final String SELECT_UNLINKED_OF_TYPE =
            "SELECT e.* FROM entities e WHERE e.etype = ?"
                    + " AND NOT EXISTS (SELECT 1 FROM relations r WHERE r.src = e.id OR r.dst = e.id) ORDER BY e.id";
    private static final String UPDATE_ATTRS = "UPDATE entities SET attrs = ? WHERE id = ?";
    private static final String UPDATE_NAME = "UPDATE entities SET name = ? WHERE id = ?";
    /** Documents that other files reference but that no file is (no mention with role self). */
    private static final String SELECT_REFERENCED_DOCUMENTS_WITHOUT_FILE =
            "SELECT e.* FROM entities e WHERE e.etype = 'document'"
                    + " AND NOT EXISTS (SELECT 1 FROM mentions m WHERE m.entity_id = e.id AND m.role = 'self') ORDER BY e.id";
    private static final String MARK_MISSING = "UPDATE entities SET attrs = json_set(attrs, '$.missing', 1) WHERE id = ?";
    private static final String DELETE = "DELETE FROM entities WHERE id = ?";
    private static final String COUNTS_BY_TYPE = "SELECT etype, COUNT(*) FROM entities GROUP BY etype";

    public long insert(Connection conn, EntityType etype, String name, String key, Map<String, Object> attrs) throws SQLException {
        return Db.insert(conn, INSERT, etype.value(), name, key, Json.write(attrs));
    }

    public EntityRow findById(Connection conn, long id) throws SQLException {
        return Db.first(conn, SELECT_BY_ID, EntitiesDao::map, id);
    }

    public EntityRow findByTypeAndKey(Connection conn, EntityType etype, String key) throws SQLException {
        return Db.first(conn, SELECT_BY_TYPE_AND_KEY, EntitiesDao::map, etype.value(), key);
    }

    /** Entities of one type that have no relation at all. */
    public List<EntityRow> findUnlinkedOfType(Connection conn, EntityType etype) throws SQLException {
        return Db.list(conn, SELECT_UNLINKED_OF_TYPE, EntitiesDao::map, etype.value());
    }

    public void updateAttrs(Connection conn, long id, Map<String, Object> attrs) throws SQLException {
        Db.update(conn, UPDATE_ATTRS, Json.write(attrs), id);
    }

    public void updateName(Connection conn, long id, String name) throws SQLException {
        Db.update(conn, UPDATE_NAME, name, id);
    }

    /** Flags a document that other documents reference but no copy of which was found. */
    public List<EntityRow> findReferencedDocumentsWithoutFile(Connection conn) throws SQLException {
        return Db.list(conn, SELECT_REFERENCED_DOCUMENTS_WITHOUT_FILE, EntitiesDao::map);
    }

    public void markMissing(Connection conn, long id) throws SQLException {
        Db.update(conn, MARK_MISSING, id);
    }

    public void delete(Connection conn, long id) throws SQLException {
        Db.update(conn, DELETE, id);
    }

    /** etype -> number of entities. */
    public Map<String, Long> countsByType(Connection conn) throws SQLException {
        return Db.counts(conn, COUNTS_BY_TYPE);
    }

    /** Maps a row of {@code entities}; also used by queries that add a {@code degree} column. */
    static EntityRow map(ResultSet rs) throws SQLException {
        EntityRow e = new EntityRow();
        e.id = rs.getLong("id");
        e.etype = EntityType.fromValue(rs.getString("etype"));
        e.name = rs.getString("name");
        e.key = rs.getString("key");
        e.attrs = Json.readMap(rs.getString("attrs"));
        return e;
    }
}
