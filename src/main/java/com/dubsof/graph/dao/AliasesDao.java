package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.AliasRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** All SQL for the {@code aliases} table. */
public class AliasesDao {

    private static final String DELETE_ALL = "DELETE FROM aliases";
    /** One alias per (entity, spelling): the most common matching rule, the average confidence, the count. */
    private static final String INSERT_FROM_MENTIONS =
            "INSERT INTO aliases (entity_id, alias, method, confidence, count)"
                    + " SELECT entity_id, surface,"
                    + "   (SELECT m2.method FROM mentions m2 WHERE m2.entity_id = m.entity_id AND m2.surface = m.surface"
                    + "    GROUP BY m2.method ORDER BY COUNT(*) DESC LIMIT 1),"
                    + "   ROUND(AVG(confidence), 3), COUNT(*)"
                    + " FROM mentions m WHERE entity_id IS NOT NULL GROUP BY entity_id, surface";
    private static final String DELETE_BY_ENTITY = "DELETE FROM aliases WHERE entity_id = ?";
    private static final String SELECT_BY_ENTITY = "SELECT * FROM aliases WHERE entity_id = ? ORDER BY count DESC";
    /** Spellings long enough to search for in free text, of companies and people, excluding one entity key. */
    private static final String SELECT_SEARCHABLE =
            "SELECT a.*, e.etype AS entity_type FROM aliases a JOIN entities e ON e.id = a.entity_id"
                    + " WHERE e.etype IN ('company', 'person') AND length(a.alias) >= ? AND a.alias NOT LIKE '%.%.%'"
                    + " AND e.key != ?";

    /** Recomputes all aliases from the resolved mentions. */
    public void rebuild(Connection conn) throws SQLException {
        Db.update(conn, DELETE_ALL);
        Db.update(conn, INSERT_FROM_MENTIONS);
    }

    public void deleteByEntity(Connection conn, long entityId) throws SQLException {
        Db.update(conn, DELETE_BY_ENTITY, entityId);
    }

    public List<AliasRow> findByEntity(Connection conn, long entityId) throws SQLException {
        return Db.list(conn, SELECT_BY_ENTITY, AliasesDao::map, entityId);
    }

    /** Company/person spellings of at least {@code minLength} characters, except those of the entity with {@code excludedKey}. */
    public List<AliasRow> findSearchable(Connection conn, int minLength, String excludedKey) throws SQLException {
        return Db.list(conn, SELECT_SEARCHABLE, AliasesDao::mapWithType, minLength, excludedKey);
    }

    private static AliasRow map(ResultSet rs) throws SQLException {
        AliasRow a = new AliasRow();
        a.entityId = rs.getLong("entity_id");
        a.alias = rs.getString("alias");
        a.method = rs.getString("method");
        a.confidence = rs.getDouble("confidence");
        a.count = rs.getLong("count");
        return a;
    }

    private static AliasRow mapWithType(ResultSet rs) throws SQLException {
        AliasRow a = map(rs);
        a.entityType = EntityType.fromValue(rs.getString("entity_type"));
        return a;
    }
}
