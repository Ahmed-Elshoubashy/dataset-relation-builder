package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.EntityFactRow;
import com.dubsof.graph.dao.row.FactRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** All SQL for the {@code facts} table. */
public class FactsDao {

    private static final String INSERT = "INSERT INTO facts (file_id, src, rel, dst) VALUES (?, ?, ?, ?)";
    private static final String SELECT_WITH_RELATIONS =
            "SELECT * FROM facts WHERE rel IN (SELECT value FROM json_each(?)) ORDER BY id";
    /** Facts whose two mentions are both resolved, expressed between entities. */
    private static final String SELECT_BETWEEN_ENTITIES =
            "SELECT f.file_id, ms.entity_id AS src_entity, f.rel, md.entity_id AS dst_entity FROM facts f"
                    + " JOIN mentions ms ON ms.id = f.src JOIN mentions md ON md.id = f.dst"
                    + " WHERE ms.entity_id IS NOT NULL AND md.entity_id IS NOT NULL ORDER BY f.id";

    public void insert(Connection conn, long fileId, long srcMention, RelationType rel, long dstMention) throws SQLException {
        Db.update(conn, INSERT, fileId, srcMention, rel.value(), dstMention);
    }

    public List<FactRow> findWithRelations(Connection conn, List<RelationType> rels) throws SQLException {
        List<String> values = new ArrayList<>();
        for (RelationType r : rels) {
            values.add(r.value());
        }
        return Db.list(conn, SELECT_WITH_RELATIONS, FactsDao::map, Json.write(values));
    }

    public List<EntityFactRow> findBetweenEntities(Connection conn) throws SQLException {
        return Db.list(conn, SELECT_BETWEEN_ENTITIES, FactsDao::mapEntityFact);
    }

    private static FactRow map(ResultSet rs) throws SQLException {
        FactRow f = new FactRow();
        f.id = rs.getLong("id");
        f.fileId = rs.getLong("file_id");
        f.src = rs.getLong("src");
        f.rel = RelationType.fromValue(rs.getString("rel"));
        f.dst = rs.getLong("dst");
        return f;
    }

    private static EntityFactRow mapEntityFact(ResultSet rs) throws SQLException {
        EntityFactRow f = new EntityFactRow();
        f.fileId = rs.getLong("file_id");
        f.srcEntityId = rs.getLong("src_entity");
        f.rel = RelationType.fromValue(rs.getString("rel"));
        f.dstEntityId = rs.getLong("dst_entity");
        return f;
    }
}
