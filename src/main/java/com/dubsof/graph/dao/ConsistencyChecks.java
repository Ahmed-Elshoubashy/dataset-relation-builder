package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.MisfiledDocumentRow;
import com.dubsof.graph.dao.row.MultiCustomerDocumentRow;
import com.dubsof.graph.db.Db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Multi-table queries behind the relate stage's data-quality checks. */
public class ConsistencyChecks {

    /** Documents that other documents reference but that no file is. */
    private static final String DOCUMENTS_WITHOUT_FILE =
            "SELECT e.* FROM entities e WHERE e.etype = 'document'"
                    + " AND NOT EXISTS (SELECT 1 FROM mentions m WHERE m.entity_id = e.id AND m.role = 'self') ORDER BY e.id";
    private static final String MULTI_CUSTOMER_DOCUMENTS =
            "SELECT r.src, e.key, GROUP_CONCAT(DISTINCT c.name) AS companies, COUNT(DISTINCT r.dst) AS n"
                    + " FROM relations r JOIN entities e ON e.id = r.src JOIN entities c ON c.id = r.dst"
                    + " WHERE r.rel = 'ISSUED_TO' GROUP BY r.src HAVING n > 1";
    /** Documents whose copies disagree on totals, dates or job titles. */
    private static final String DOCUMENTS_WITH_CONFLICTS =
            "SELECT * FROM entities WHERE etype = 'document' AND attrs LIKE '%conflicts%' ORDER BY id";
    /** Parameter: the owner company, which may appear on any document. */
    private static final String MISFILED_DOCUMENTS =
            "SELECT DISTINCT d.id, d.key, fc.name AS folder_company, c.name AS addressed_to"
                    + " FROM relations fr JOIN entities d ON d.id = fr.dst"
                    + " JOIN relations h ON h.src IN (SELECT src FROM relations WHERE rel = 'HAS_PROJECT')"
                    + "   AND h.rel = 'HAS_PROJECT' AND h.dst = fr.src"
                    + " JOIN entities fc ON fc.id = h.src"
                    + " JOIN relations i ON i.src = d.id AND i.rel IN ('ISSUED_TO', 'ADDRESSED_TO')"
                    + " JOIN entities c ON c.id = i.dst"
                    + " WHERE fr.rel = 'HAS_DOCUMENT' AND i.dst != h.src AND i.dst != ?";
    /** Projects only known from documents or screenshots, not from a project folder. */
    private static final String UNFILED_PROJECTS =
            "SELECT * FROM entities WHERE etype = 'project' AND json_extract(attrs, '$.source') != 'folder' ORDER BY id";

    public List<EntityRow> findDocumentsWithoutFile(Connection conn) throws SQLException {
        return Db.list(conn, DOCUMENTS_WITHOUT_FILE, EntitiesDao::map);
    }

    public List<MultiCustomerDocumentRow> findMultiCustomerDocuments(Connection conn) throws SQLException {
        return Db.list(conn, MULTI_CUSTOMER_DOCUMENTS, ConsistencyChecks::mapMultiCustomer);
    }

    public List<EntityRow> findDocumentsWithConflicts(Connection conn) throws SQLException {
        return Db.list(conn, DOCUMENTS_WITH_CONFLICTS, EntitiesDao::map);
    }

    public List<MisfiledDocumentRow> findMisfiledDocuments(Connection conn, long ownerId) throws SQLException {
        return Db.list(conn, MISFILED_DOCUMENTS, ConsistencyChecks::mapMisfiled, ownerId);
    }

    public List<EntityRow> findUnfiledProjects(Connection conn) throws SQLException {
        return Db.list(conn, UNFILED_PROJECTS, EntitiesDao::map);
    }

    private static MultiCustomerDocumentRow mapMultiCustomer(ResultSet rs) throws SQLException {
        MultiCustomerDocumentRow d = new MultiCustomerDocumentRow();
        d.documentId = rs.getLong("src");
        d.documentKey = rs.getString("key");
        d.companies = rs.getString("companies");
        return d;
    }

    private static MisfiledDocumentRow mapMisfiled(ResultSet rs) throws SQLException {
        MisfiledDocumentRow d = new MisfiledDocumentRow();
        d.documentId = rs.getLong("id");
        d.documentKey = rs.getString("key");
        d.folderCompany = rs.getString("folder_company");
        d.addressedTo = rs.getString("addressed_to");
        return d;
    }
}
