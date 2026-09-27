package com.dubsof.graph;

import com.dubsof.graph.db.Db;
import com.dubsof.graph.pipeline.Pipeline;
import com.dubsof.graph.read.OcrBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds a graph from a small dataset laid out nothing like john-doe, with the real pipeline,
 * offline (no OCR, rules adjudicator, no API key). It checks general rules, not counts.
 *
 * The dataset (src/test/resources/datasets/generic) belongs to a US company, Harbor Robotics Inc:
 * <ul>
 *   <li>Sales/Invoices/HR-1042.pdf: a $ invoice to Müller GmbH, attention José Müller;</li>
 *   <li>Mail/: an order from José Müller (mueller-gmbh.de), a reply from Dana Price (harborrobotics.com,
 *       cc a gmail address) to "Jose Mueller", and a follow-up mentioning "Mueller GmbH";</li>
 *   <li>Notes/: free-text call notes naming José Müller, Mueller GmbH and Dana Price.</li>
 * </ul>
 * There is no Customers/ folder and no profile.json.
 */
class GenericDatasetTest {

    @TempDir
    static Path work;
    static Connection conn;
    static Pipeline.Result result;

    @BeforeAll
    static void build() throws Exception {
        File dataset = Paths.get(GenericDatasetTest.class.getResource("/datasets/generic").toURI()).toFile();
        Pipeline.Options options = new Pipeline.Options();
        options.ocr = OcrBackend.NONE;
        result = Pipeline.build(dataset, work.resolve("graph.db").toFile(), options, (step, stage, detail) -> { });
        conn = Db.open(result.built, true);
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    @Test
    void everyFileIsRead() throws Exception {
        assertEquals(5, Db.number(conn, "SELECT COUNT(*) FROM files WHERE status = 'ok'"));
    }

    @Test
    void ownerIsDetected() throws Exception {
        assertEquals("Harbor Robotics Inc", owner());
        assertEquals("harborrobotics.com", Db.first(conn,
                "SELECT json_extract(attrs, '$.domain') FROM entities WHERE json_extract(attrs, '$.role') = 'owner'", rs -> rs.getString(1)));
    }

    @Test
    void ownerStaffWorkForTheOwner() throws Exception {
        assertTrue(relationExists("Dana Price", "WORKS_FOR", owner()));
    }

    @Test
    void everyEmailSenderIsAPerson() throws Exception {
        for (String sender : new String[] {"José Müller", "Dana Price"}) {
            assertEquals(1, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'person' AND name = ?", sender), sender);
            assertTrue(relationExists(sender, "SENT", null), sender + " sent an e-mail");
        }
    }

    @Test
    void accentsAndTransliterationsAreOnePerson() throws Exception {
        // "José Müller" (order, invoice) and "Jose Mueller" (replies), both at Müller GmbH
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'person' AND name = 'Jose Mueller'"));
    }

    @Test
    void customerSpellingsAndDomainAreOneCompany() throws Exception {
        // "Müller GmbH" (bill-to, order), "Mueller GmbH" (follow-up, notes) and mueller-gmbh.de (senders)
        long companies = Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'company'"
                + " AND (name LIKE 'M%ller GmbH' OR name LIKE 'mueller%')");
        assertEquals(1, companies);
    }

    @Test
    void freeMailDomainIsNotACompany() throws Exception {
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'company' AND name = 'gmail.com'"));
    }

    @Test
    void noCompanyMentionIsUnresolvedExceptFreeMail() throws Exception {
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM mentions WHERE etype = 'company' AND entity_id IS NULL"
                + " AND method != 'generic_domain'"));
    }

    @Test
    void invoiceTotalIsRead() throws Exception {
        assertEquals(4250.0, invoiceAttr("total"));
    }

    @Test
    void invoiceCurrencyIsRead() throws Exception {
        assertEquals("USD", invoiceAttr("currency"));
    }

    @Test
    void namesInTheCallNotesAreLinked() throws Exception {
        String notes = Db.first(conn, "SELECT e.name FROM mentions m JOIN files f ON f.id = m.file_id"
                + " JOIN entities e ON e.id = m.entity_id WHERE m.role = 'self' AND f.path LIKE 'Notes/%'", rs -> rs.getString(1));
        List<String> linked = Db.list(conn, "SELECT d.name FROM relations r JOIN entities s ON s.id = r.src"
                + " JOIN entities d ON d.id = r.dst WHERE s.name = ? AND r.rel = 'MENTIONS'", rs -> rs.getString(1), notes);
        assertTrue(linked.contains("José Müller"), "linked: " + linked);
        assertTrue(linked.stream().anyMatch(name -> name.endsWith("ller GmbH")), "linked: " + linked);
    }

    private static String owner() throws Exception {
        return Db.first(conn, "SELECT name FROM entities WHERE json_extract(attrs, '$.role') = 'owner'", rs -> rs.getString(1));
    }

    private static Object invoiceAttr(String attr) throws Exception {
        return Db.first(conn, "SELECT json_extract(e.attrs, '$." + attr + "') FROM entities e"
                + " WHERE e.etype = 'document' AND json_extract(e.attrs, '$.doc_type') = 'invoice'", rs -> rs.getObject(1));
    }

    /** True when "src REL dst" exists between entities with these names (dst null: any target). */
    private static boolean relationExists(String src, String rel, String dst) throws Exception {
        return Db.number(conn, "SELECT COUNT(*) FROM relations r JOIN entities s ON s.id = r.src JOIN entities d ON d.id = r.dst"
                + " WHERE s.name = ? AND r.rel = ? AND (? IS NULL OR d.name = ?)", src, rel, dst, dst) > 0;
    }
}
