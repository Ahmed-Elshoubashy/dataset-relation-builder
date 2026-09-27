package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.EvidenceRow;
import com.dubsof.graph.dao.row.FileMentionRow;
import com.dubsof.graph.dao.row.NeighbourRow;
import com.dubsof.graph.dao.row.RelatedEntityRow;
import com.dubsof.graph.dao.row.RelationRow;
import com.dubsof.graph.dao.row.SourceRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The explorer's read queries that join several tables.
 * Optional filters use "(? IS NULL OR column = ?)" and lists are passed as one JSON array
 * ("IN (SELECT value FROM json_each(?))"), so every query is a fixed constant.
 */
public class GraphQueries {

    /** Number of relations an entity takes part in. */
    private static final String DEGREE = "(SELECT COUNT(*) FROM relations r WHERE r.src = e.id OR r.dst = e.id)";

    /** Filters: type, doc_type, and a name/key/alias search (each skipped when its parameter is NULL). */
    private static final String SEARCH_FILTER =
            " WHERE (? IS NULL OR e.etype = ?)"
                    + " AND (? IS NULL OR json_extract(e.attrs, '$.doc_type') = ?)"
                    + " AND (? IS NULL OR e.name LIKE ? OR e.key LIKE ?"
                    + "      OR EXISTS (SELECT 1 FROM aliases a WHERE a.entity_id = e.id AND a.alias LIKE ?))";
    private static final String SEARCH_ENTITIES =
            "SELECT e.*, " + DEGREE + " AS degree FROM entities e" + SEARCH_FILTER
                    + " ORDER BY degree DESC, e.name LIMIT ? OFFSET ?";
    private static final String COUNT_ENTITIES = "SELECT COUNT(*) FROM entities e" + SEARCH_FILTER;

    private static final String ENTITY_WITH_DEGREE = "SELECT e.*, " + DEGREE + " AS degree FROM entities e WHERE e.id = ?";
    private static final String ENTITIES_WITH_DEGREE =
            "SELECT e.*, " + DEGREE + " AS degree FROM entities e"
                    + " WHERE e.id IN (SELECT value FROM json_each(?)) ORDER BY e.id";

    /** Both directions of every relation of one entity, strongest first. */
    private static final String RELATED_ENTITIES =
            "SELECT r.rel, r.weight, r.derived, 'out' AS dir, o.id, o.etype AS type, o.name,"
                    + "   json_extract(o.attrs, '$.doc_type') AS doc_type"
                    + " FROM relations r JOIN entities o ON o.id = r.dst WHERE r.src = ?"
                    + " UNION ALL"
                    + " SELECT r.rel, r.weight, r.derived, 'in' AS dir, o.id, o.etype AS type, o.name,"
                    + "   json_extract(o.attrs, '$.doc_type') AS doc_type"
                    + " FROM relations r JOIN entities o ON o.id = r.src WHERE r.dst = ?"
                    + " ORDER BY weight DESC";

    /** Files mentioning an entity: the file that *is* the entity first, then by path. */
    private static final String SOURCES =
            "SELECT f.id, f.path, f.kind, f.status, f.text_source, m.role, m.surface, m.method, m.confidence"
                    + " FROM mentions m JOIN files f ON f.id = m.file_id WHERE m.entity_id = ?"
                    + " ORDER BY m.role = 'self' DESC, f.path LIMIT ?";

    /** Companies and their filed projects (projects only seen in screenshots are left out). */
    private static final String OVERVIEW_NODE_IDS =
            "SELECT id FROM entities WHERE etype = 'project' AND json_extract(attrs, '$.source') = 'folder'"
                    + " UNION SELECT r.src FROM relations r JOIN entities p ON p.id = r.dst"
                    + " WHERE r.rel = 'HAS_PROJECT' AND json_extract(p.attrs, '$.source') = 'folder'";

    /** Neighbours of one entity of the given types: projects, companies, products, people, then documents; strongest first. */
    /**
     * Neighbours, one row each (a neighbour linked twice takes one place), projects and companies first,
     * then the strongest link; ties by id, so the same call always returns the same neighbours.
     */
    private static final String NEIGHBOUR_IDS =
            "SELECT CASE WHEN r.src = ? THEN r.dst ELSE r.src END AS other FROM relations r"
                    + " JOIN entities e ON e.id = CASE WHEN r.src = ? THEN r.dst ELSE r.src END"
                    + " WHERE (r.src = ? OR r.dst = ?) AND (? OR r.derived = 0)"
                    + "   AND e.etype IN (SELECT value FROM json_each(?))"
                    + " GROUP BY other"
                    + " ORDER BY CASE MIN(e.etype) WHEN 'project' THEN 0 WHEN 'company' THEN 1 WHEN 'product' THEN 2"
                    + "   WHEN 'person' THEN 3 ELSE 4 END, MAX(r.weight) DESC, other LIMIT ?";
    private static final String RELATION_BY_ID = "SELECT * FROM relations WHERE id = ?";
    /** The files behind a relation, each with the mentions of the relation's two ends in it. */
    private static final String RELATION_EVIDENCE =
            "SELECT f.id AS file_id, f.path, f.kind, f.status, m.entity_id, m.surface, m.role, m.method, m.confidence"
                    + " FROM relation_evidence ev JOIN relations r ON r.id = ev.relation_id JOIN files f ON f.id = ev.file_id"
                    + " LEFT JOIN mentions m ON m.file_id = f.id AND m.entity_id IN (r.src, r.dst)"
                    + " WHERE ev.relation_id = ? ORDER BY f.path, m.entity_id = r.src DESC, m.role LIMIT ?";
    /** Derived INVOLVED_IN (person, project): the project's documents the person authored, sent or received. */
    private static final String INVOLVED_IN_VIA =
            "SELECT DISTINCT d.* FROM relations r JOIN relations h ON h.dst = r.dst AND h.rel = 'HAS_DOCUMENT'"
                    + " JOIN entities d ON d.id = r.dst"
                    + " WHERE r.rel IN ('AUTHORED', 'SENT', 'RECEIVED') AND r.src = ? AND h.src = ? ORDER BY d.key LIMIT ?";
    /** Derived USES_PRODUCT (project, product): the project's documents that list or describe the product. */
    private static final String USES_PRODUCT_VIA =
            "SELECT DISTINCT d.* FROM relations h JOIN relations r ON r.src = h.dst AND r.rel IN ('LISTS_PRODUCT', 'DESCRIBES')"
                    + " JOIN entities d ON d.id = h.dst"
                    + " WHERE h.rel = 'HAS_DOCUMENT' AND h.src = ? AND r.dst = ? ORDER BY d.key LIMIT ?";
    /** Derived PURCHASED_OR_QUOTED (company, product): documents issued to the company that list the product. */
    private static final String PURCHASED_OR_QUOTED_VIA =
            "SELECT DISTINCT d.* FROM relations i JOIN relations l ON l.src = i.src AND l.rel = 'LISTS_PRODUCT'"
                    + " JOIN entities d ON d.id = i.src"
                    + " WHERE i.rel = 'ISSUED_TO' AND i.dst = ? AND l.dst = ? ORDER BY d.key LIMIT ?";

    /** Every relation of an entity to a neighbour of an allowed type: which neighbour, its type, the relation and direction. */
    private static final String NEIGHBOUR_RELATIONS =
            "SELECT DISTINCT e.id, e.etype, r.rel, CASE WHEN r.src = ? THEN 'out' ELSE 'in' END AS dir FROM relations r"
                    + " JOIN entities e ON e.id = CASE WHEN r.src = ? THEN r.dst ELSE r.src END"
                    + " WHERE (r.src = ? OR r.dst = ?) AND (? OR r.derived = 0)"
                    + "   AND e.etype IN (SELECT value FROM json_each(?))";

    /** Relations whose two ends are both in a set of entities. */
    private static final String RELATIONS_AMONG =
            "SELECT * FROM relations"
                    + " WHERE src IN (SELECT value FROM json_each(?)) AND dst IN (SELECT value FROM json_each(?))"
                    + "   AND (? OR derived = 0) ORDER BY src, dst, rel";

    /** The entity whose name or aliases match a search most often. */
    private static final String BEST_MATCH_ID =
            "SELECT e.id FROM entities e LEFT JOIN aliases a ON a.entity_id = e.id"
                    + " WHERE e.name LIKE ? OR a.alias LIKE ? GROUP BY e.id ORDER BY COUNT(*) DESC LIMIT 1";

    /** Every mention in one file, with the entity it resolved to. */
    private static final String FILE_MENTIONS =
            "SELECT m.surface, m.role, m.method, m.confidence, e.id AS entity_id, e.name AS entity_name, e.etype AS entity_type"
                    + " FROM mentions m LEFT JOIN entities e ON e.id = m.entity_id WHERE m.file_id = ?";

    /**
     * Entities matching the filters, most connected first. Each filter is optional (null = any).
     *
     * @param search text to look for in names, keys and aliases
     */
    public List<EntityRow> searchEntities(Connection conn, String type, String docType, String search, int limit, int offset)
            throws SQLException {
        String like = search == null ? null : "%" + search + "%";
        return Db.list(conn, SEARCH_ENTITIES, GraphQueries::mapEntityWithDegree,
                type, type, docType, docType, like, like, like, like, limit, offset);
    }

    public long countEntities(Connection conn, String type, String docType, String search) throws SQLException {
        String like = search == null ? null : "%" + search + "%";
        return Db.number(conn, COUNT_ENTITIES, type, type, docType, docType, like, like, like, like);
    }

    public EntityRow findEntityWithDegree(Connection conn, long id) throws SQLException {
        return Db.first(conn, ENTITY_WITH_DEGREE, GraphQueries::mapEntityWithDegree, id);
    }

    public List<EntityRow> findEntitiesWithDegree(Connection conn, Collection<Long> ids) throws SQLException {
        return Db.list(conn, ENTITIES_WITH_DEGREE, GraphQueries::mapEntityWithDegree, Json.write(ids));
    }

    public List<RelatedEntityRow> findRelated(Connection conn, long entityId) throws SQLException {
        return Db.list(conn, RELATED_ENTITIES, GraphQueries::mapRelated, entityId, entityId);
    }

    public List<SourceRow> findSources(Connection conn, long entityId, int limit) throws SQLException {
        return Db.list(conn, SOURCES, GraphQueries::mapSource, entityId, limit);
    }

    public List<Long> findOverviewNodeIds(Connection conn) throws SQLException {
        return Db.list(conn, OVERVIEW_NODE_IDS, GraphQueries::mapFirstLong);
    }

    /** Up to {@code limit} neighbours of {@code entityId} whose type is in {@code types}. */
    public List<Long> findNeighbourIds(Connection conn, long entityId, boolean includeDerived, List<String> types, int limit)
            throws SQLException {
        return Db.list(conn, NEIGHBOUR_IDS, GraphQueries::mapFirstLong,
                entityId, entityId, entityId, entityId, includeDerived ? 1 : 0, Json.write(types), limit);
    }

    /** Every relation between {@code entityId} and a neighbour whose type is in {@code types}. */
    public List<NeighbourRow> findNeighbourRelations(Connection conn, long entityId, boolean includeDerived, List<String> types)
            throws SQLException {
        return Db.list(conn, NEIGHBOUR_RELATIONS, GraphQueries::mapNeighbour,
                entityId, entityId, entityId, entityId, includeDerived ? 1 : 0, Json.write(types));
    }

    public RelationRow findRelation(Connection conn, long id) throws SQLException {
        return Db.first(conn, RELATION_BY_ID, RelationsDao::map, id);
    }

    public List<EvidenceRow> findRelationEvidence(Connection conn, long relationId, int limit) throws SQLException {
        return Db.list(conn, RELATION_EVIDENCE, GraphQueries::mapEvidence, relationId, limit);
    }

    /**
     * The documents a derived relation was inferred from (derived relations have no evidence files of their
     * own); empty for a relation type that is not derived.
     */
    public List<EntityRow> findDerivedVia(Connection conn, RelationRow relation, int limit) throws SQLException {
        String sql = relation.rel == RelationType.INVOLVED_IN ? INVOLVED_IN_VIA
                : relation.rel == RelationType.USES_PRODUCT ? USES_PRODUCT_VIA
                : relation.rel == RelationType.PURCHASED_OR_QUOTED ? PURCHASED_OR_QUOTED_VIA : null;
        return sql == null ? new ArrayList<EntityRow>() : Db.list(conn, sql, EntitiesDao::map, relation.src, relation.dst, limit);
    }

    public List<RelationRow> findRelationsAmong(Connection conn, Collection<Long> ids, boolean includeDerived) throws SQLException {
        String json = Json.write(ids);
        return Db.list(conn, RELATIONS_AMONG, RelationsDao::map, json, json, includeDerived ? 1 : 0);
    }

    /** Id of the entity best matching {@code search}, or null. */
    public Long findBestMatchId(Connection conn, String search) throws SQLException {
        String like = "%" + search + "%";
        return Db.first(conn, BEST_MATCH_ID, GraphQueries::mapFirstLong, like, like);
    }

    public List<FileMentionRow> findFileMentions(Connection conn, long fileId) throws SQLException {
        return Db.list(conn, FILE_MENTIONS, GraphQueries::mapFileMention, fileId);
    }

    // ------------------------------------------------------------------ mappers

    private static EntityRow mapEntityWithDegree(ResultSet rs) throws SQLException {
        EntityRow e = EntitiesDao.map(rs);
        e.degree = rs.getLong("degree");
        return e;
    }

    private static RelatedEntityRow mapRelated(ResultSet rs) throws SQLException {
        RelatedEntityRow r = new RelatedEntityRow();
        r.rel = rs.getString("rel");
        r.weight = rs.getLong("weight");
        r.derived = rs.getLong("derived");
        r.dir = rs.getString("dir");
        r.id = rs.getLong("id");
        r.type = rs.getString("type");
        r.name = rs.getString("name");
        r.docType = rs.getString("doc_type");
        return r;
    }

    private static SourceRow mapSource(ResultSet rs) throws SQLException {
        SourceRow s = new SourceRow();
        s.fileId = rs.getLong("id");
        s.path = rs.getString("path");
        s.kind = rs.getString("kind");
        s.status = rs.getString("status");
        s.textSource = rs.getString("text_source");
        s.role = rs.getString("role");
        s.surface = rs.getString("surface");
        s.method = rs.getString("method");
        s.confidence = Db.doubleOrNull(rs, "confidence");
        return s;
    }

    private static FileMentionRow mapFileMention(ResultSet rs) throws SQLException {
        FileMentionRow m = new FileMentionRow();
        m.surface = rs.getString("surface");
        m.role = rs.getString("role");
        m.method = rs.getString("method");
        m.confidence = Db.doubleOrNull(rs, "confidence");
        m.entityId = Db.longOrNull(rs, "entity_id");
        m.entityName = rs.getString("entity_name");
        m.entityType = rs.getString("entity_type");
        return m;
    }

    private static EvidenceRow mapEvidence(ResultSet rs) throws SQLException {
        EvidenceRow row = new EvidenceRow();
        row.fileId = rs.getLong("file_id");
        row.path = rs.getString("path");
        row.kind = rs.getString("kind");
        row.status = rs.getString("status");
        row.entityId = Db.longOrNull(rs, "entity_id");
        row.surface = rs.getString("surface");
        row.role = rs.getString("role");
        row.method = rs.getString("method");
        row.confidence = rs.getObject("confidence") == null ? null : rs.getDouble("confidence");
        return row;
    }

    private static NeighbourRow mapNeighbour(ResultSet rs) throws SQLException {
        NeighbourRow row = new NeighbourRow();
        row.id = rs.getLong("id");
        row.type = rs.getString("etype");
        row.rel = rs.getString("rel");
        row.dir = rs.getString("dir");
        return row;
    }

    private static Long mapFirstLong(ResultSet rs) throws SQLException {
        return rs.getLong(1);
    }
}
