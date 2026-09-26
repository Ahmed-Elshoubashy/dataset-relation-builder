package com.dubsof.graph;

import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** End-to-end checks on a graph built from john-doe (run "run --data ../../john-doe" first). */
class PipelineTest {

    private static final String BILL_TO_VS_FOLDER =
            "SELECT fm.entity_id AS folder_co, bm.entity_id AS billed FROM mentions bm"
                    + " JOIN mentions fm ON fm.file_id = bm.file_id AND fm.etype = 'company' AND fm.role = 'folder'"
                    + " WHERE bm.role = 'bill_to'";

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        assumeTrue(Config.DB_FILE.exists(), "graph.db not built");
        conn = Db.open(Config.DB_FILE, true);
        assumeTrue(Db.number(conn, "SELECT COUNT(*) FROM entities") > 0, "graph is empty");
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void twelveCustomersPlusOwner() throws Exception {
        assertEquals(12, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype='company' AND json_extract(attrs,'$.role')='customer'"));
        assertEquals(1, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype='company' AND json_extract(attrs,'$.role')='owner'"));
    }

    @Test
    void everyCompanyMentionResolved() throws Exception {
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM mentions WHERE etype='company' AND entity_id IS NULL AND method != 'generic_domain'"));
    }

    @Test
    void billToVariantsLandOnTheFolderCustomer() throws Exception {
        // {folder company, billed company} for every bill-to line in a customer's folder
        List<long[]> rows = Db.list(conn, BILL_TO_VS_FOLDER, rs -> new long[] {rs.getLong("folder_co"), rs.getLong("billed")});
        assertTrue(rows.size() > 0);
        int agree = 0;
        for (long[] r : rows) {
            if (r[0] == r[1]) {
                agree++;
            }
        }
        assertTrue(agree / (double) rows.size() > 0.99);
    }

    @Test
    void oneProjectPerJobFolder() throws Exception {
        assertEquals(38, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype='project' AND json_extract(attrs,'$.source')='folder'"));
    }

    @Test
    void versionsCollapseIntoOneDocument() throws Exception {
        EntityRow quote = new EntitiesDao().findByTypeAndKey(conn, "document", "QUO-5238");
        assertNotNull(quote);
        assertTrue(((List<?>) quote.attrs.get("files")).size() >= 3);
    }

    @Test
    void collidingDrawingNumbersAreSplit() throws Exception {
        assertEquals(2, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE key LIKE 'DWG-9296%'"));
    }
}
