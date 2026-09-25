package com.dubsof.graph;

import com.dubsof.graph.db.Db;
import com.dubsof.graph.util.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** End-to-end checks on a graph built from john-doe (run "run --data ../../john-doe" first). */
class PipelineTest {

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        assumeTrue(Config.DB_FILE.exists(), "graph.db not built");
        conn = Db.open(Config.DB_FILE, true);
        assumeTrue(Db.count(conn, "SELECT COUNT(*) FROM entities") > 0, "graph is empty");
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void twelveCustomersPlusOwner() throws Exception {
        assertEquals(12, Db.count(conn, "SELECT COUNT(*) FROM entities WHERE etype='company' AND json_extract(attrs,'$.role')='customer'"));
        assertEquals(1, Db.count(conn, "SELECT COUNT(*) FROM entities WHERE etype='company' AND json_extract(attrs,'$.role')='owner'"));
    }

    @Test
    void everyCompanyMentionResolved() throws Exception {
        assertEquals(0, Db.count(conn, "SELECT COUNT(*) FROM mentions WHERE etype='company' AND entity_id IS NULL AND method != 'generic_domain'"));
    }

    @Test
    void billToVariantsLandOnTheFolderCustomer() throws Exception {
        List<Map<String, Object>> rows = Db.query(conn, "SELECT fm.entity_id folder_co, bm.entity_id billed FROM mentions bm"
                + " JOIN mentions fm ON fm.file_id = bm.file_id AND fm.etype='company' AND fm.role='folder' WHERE bm.role='bill_to'");
        assertTrue(rows.size() > 0);
        int agree = 0;
        for (Map<String, Object> r : rows) {
            if (r.get("folder_co").equals(r.get("billed"))) {
                agree++;
            }
        }
        assertTrue(agree / (double) rows.size() > 0.99);
    }

    @Test
    void oneProjectPerJobFolder() throws Exception {
        assertEquals(38, Db.count(conn, "SELECT COUNT(*) FROM entities WHERE etype='project' AND json_extract(attrs,'$.source')='folder'"));
    }

    @Test
    void versionsCollapseIntoOneDocument() throws Exception {
        Object attrs = Db.scalar(conn, "SELECT attrs FROM entities WHERE etype='document' AND key='QUO-5238'");
        assertNotNull(attrs);
        assertTrue(((List<?>) Json.readMap((String) attrs).get("files")).size() >= 3);
    }

    @Test
    void collidingDrawingNumbersAreSplit() throws Exception {
        assertEquals(2, Db.count(conn, "SELECT COUNT(*) FROM entities WHERE key LIKE 'DWG-9296%'"));
    }
}
