package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.RelationRow;
import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** All SQL for the {@code relations} and {@code relation_evidence} tables. */
public class RelationsDao {

    private static final String DELETE_ALL_EVIDENCE = "DELETE FROM relation_evidence";
    private static final String DELETE_ALL = "DELETE FROM relations";
    /** Adds a relation, or adds to its weight when it already exists. */
    private static final String UPSERT =
            "INSERT INTO relations (src, dst, rel, weight, derived) VALUES (?, ?, ?, ?, 0)"
                    + " ON CONFLICT(src, dst, rel) DO UPDATE SET weight = weight + excluded.weight";
    private static final String SELECT_ID = "SELECT id FROM relations WHERE src = ? AND dst = ? AND rel = ?";
    private static final String INSERT_EVIDENCE = "INSERT OR IGNORE INTO relation_evidence (relation_id, file_id) VALUES (?, ?)";
    private static final String COUNT = "SELECT COUNT(*) FROM relations";

    /** person -> project, through any document they authored / sent / received / were addressed on. */
    private static final String DERIVE_INVOLVED_IN =
            "INSERT OR IGNORE INTO relations (src, dst, rel, weight, derived)"
                    + " SELECT p, proj, 'INVOLVED_IN', COUNT(*), 1 FROM ("
                    + "   SELECT r.src p, h.src proj FROM relations r JOIN relations h ON h.dst = r.dst AND h.rel = 'HAS_DOCUMENT'"
                    + "    WHERE r.rel IN ('AUTHORED', 'SENT', 'RECEIVED')"
                    + "   UNION ALL"
                    + "   SELECT r.dst p, h.src proj FROM relations r JOIN relations h ON h.dst = r.src AND h.rel = 'HAS_DOCUMENT'"
                    + "    WHERE r.rel = 'ATTENTION_OF'"
                    + " ) GROUP BY p, proj";
    /** project -> product, through line items, specs and datasheets filed in the project. */
    private static final String DERIVE_USES_PRODUCT =
            "INSERT OR IGNORE INTO relations (src, dst, rel, weight, derived)"
                    + " SELECT h.src, r.dst, 'USES_PRODUCT', COUNT(*), 1 FROM relations h"
                    + "   JOIN relations r ON r.src = h.dst AND r.rel IN ('LISTS_PRODUCT', 'DESCRIBES')"
                    + "   JOIN entities e ON e.id = r.dst AND e.etype = 'product'"
                    + " WHERE h.rel = 'HAS_DOCUMENT' GROUP BY h.src, r.dst";
    /** company -> product: what each customer has bought or been quoted. */
    private static final String DERIVE_PURCHASED_OR_QUOTED =
            "INSERT OR IGNORE INTO relations (src, dst, rel, weight, derived)"
                    + " SELECT i.dst, l.dst, 'PURCHASED_OR_QUOTED', COUNT(*), 1 FROM relations i"
                    + "   JOIN relations l ON l.src = i.src AND l.rel = 'LISTS_PRODUCT'"
                    + " WHERE i.rel = 'ISSUED_TO' GROUP BY i.dst, l.dst";

    public void deleteAll(Connection conn) throws SQLException {
        Db.update(conn, DELETE_ALL_EVIDENCE);
        Db.update(conn, DELETE_ALL);
    }

    /** Adds {@code weight} to the relation (creating it if needed) and returns its id. */
    public long upsert(Connection conn, long src, long dst, String rel, long weight) throws SQLException {
        Db.update(conn, UPSERT, src, dst, rel, weight);
        return Db.number(conn, SELECT_ID, src, dst, rel);
    }

    public void addEvidence(Connection conn, long relationId, long fileId) throws SQLException {
        Db.update(conn, INSERT_EVIDENCE, relationId, fileId);
    }

    /** Adds the shortcut relations INVOLVED_IN, USES_PRODUCT and PURCHASED_OR_QUOTED. */
    public void deriveShortcuts(Connection conn) throws SQLException {
        Db.update(conn, DERIVE_INVOLVED_IN);
        Db.update(conn, DERIVE_USES_PRODUCT);
        Db.update(conn, DERIVE_PURCHASED_OR_QUOTED);
    }

    public long count(Connection conn) throws SQLException {
        return Db.number(conn, COUNT);
    }

    /** Maps a row of {@code relations}; used by the explorer's graph queries. */
    static RelationRow map(ResultSet rs) throws SQLException {
        RelationRow r = new RelationRow();
        r.id = rs.getLong("id");
        r.src = rs.getLong("src");
        r.dst = rs.getLong("dst");
        r.rel = rs.getString("rel");
        r.weight = rs.getLong("weight");
        r.derived = rs.getLong("derived");
        return r;
    }
}
