package com.dubsof.graph;

import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.ingest.FileKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolver and Relator on a small, hand-made dataset: two copies of one invoice in Acme's job folder,
 * an e-mail about it (one sender on gmail.com), and an unrelated note. Runs the real extract, resolve
 * and relate stages on a temporary graph.db.
 */
class GraphBuildTest {

    static final String INVOICE =
            "Meridian Packaging Systems Ltd\n"
            + "INVOICE\n"
            + "No: INV-8034\n"
            + "Date: 11 Mar 2024\n"
            + "Job: Shrink Wrap Retrofit\n"
            + "Quote Ref: QUO-9999\n"
            + "BILL TO:\n"
            + "ACME Corp\n"
            + "240 Priory Lane\n"
            + "Wolverhampton\n"
            + "Attn: Thomas Bianchi\n"
            + "Description Qty Unit Unit Price Line Total\n"
            + "Case Sealer CS-110 1 unit £3,344.00 £3,344.00\n"
            + "Subtotal: £3,344.00\n"
            + "TOTAL: £3,344.00\n";

    static final String EMAIL =
            "From: Sam Jones <sam.jones@gmail.com>\n"
            + "To: Thomas Bianchi <thomas.bianchi@acmecorp.com>\n"
            + "Subject: RE: Shrink Wrap Retrofit — schedule update\n"
            + "\n"
            + "Hi Thomas, the invoice INV-8034 is attached.\n";

    static final String JOB = "Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/Invoices/";

    @TempDir
    static Path dir;
    static Connection conn;
    static final EntitiesDao entitiesDao = new EntitiesDao();

    @BeforeAll
    static void build() throws Exception {
        conn = TestGraph.create(dir);
        TestGraph.addFile(conn, JOB + "INV-8034_Acme Corporation.pdf", FileKind.PDF, INVOICE);
        TestGraph.addFile(conn, JOB + "INV-8034_v2.pdf", FileKind.PDF, INVOICE);
        TestGraph.addFile(conn, "Admin/Mail/re_schedule.eml", FileKind.EML, EMAIL);
        TestGraph.addFile(conn, "Admin/Notes/random_note.txt", FileKind.TEXT, "Nothing to see here.");
        TestGraph.buildGraph(conn);
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    // ---------------------------------------------------------------- resolve

    @Test
    void spellingsOfTheCustomerBecomeOneCompany() throws Exception {
        // "Acme Corporation" (folder and filename), "ACME Corp" (bill-to), "acmecorp.com" (e-mail)
        EntityRow acme = entitiesDao.findByTypeAndKey(conn, EntityType.COMPANY, "acme");
        assertNotNull(acme);
        assertEquals("customer", acme.attrs.get("role"));
        assertEquals(3, Db.number(conn, "SELECT COUNT(DISTINCT surface) FROM mentions WHERE entity_id = ?", acme.id));
        assertEquals("normalized", Db.first(conn, "SELECT method FROM mentions WHERE surface = 'ACME Corp'", rs -> rs.getString(1)));
        assertEquals("email_domain", Db.first(conn, "SELECT method FROM mentions WHERE surface = 'acmecorp.com'", rs -> rs.getString(1)));
    }

    @Test
    void onlyTheOwnerAndTheCustomerAreCompanies() throws Exception {
        assertEquals(2, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'company'"));
        assertEquals(1, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'company' AND json_extract(attrs, '$.role') = 'owner'"));
    }

    @Test
    void freeMailDomainIsNotACompany() throws Exception {
        assertEquals("generic_domain", Db.first(conn, "SELECT method FROM mentions WHERE surface = 'gmail.com'", rs -> rs.getString(1)));
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM mentions WHERE surface = 'gmail.com' AND entity_id IS NOT NULL"));
    }

    @Test
    void jobFolderJobFieldAndEmailSubjectAreOneProject() throws Exception {
        EntityRow project = entitiesDao.findByTypeAndKey(conn, EntityType.PROJECT, "JOB-2023-0003");
        assertNotNull(project);
        assertEquals(1, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'project'"));
        Map<String, Long> methods = Db.counts(conn, "SELECT method, COUNT(*) FROM mentions WHERE entity_id = ? GROUP BY method", project.id);
        assertTrue(methods.containsKey("job_id"));          // the folder
        assertTrue(methods.containsKey("title+company"));   // "Job: Shrink Wrap Retrofit" on the invoice billed to Acme
        assertTrue(methods.containsKey("unique_title"));    // the e-mail subject
    }

    @Test
    void samePersonFromEmailAndAttnLine() throws Exception {
        assertEquals(1, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'person' AND name = 'Thomas Bianchi'"));
        EntityRow thomas = Db.first(conn, "SELECT * FROM entities WHERE etype = 'person' AND name = 'Thomas Bianchi'",
                rs -> entitiesDao.findById(conn, rs.getLong("id")));
        assertEquals(List.of("thomas.bianchi@acmecorp.com"), thomas.attrs.get("emails"));
        // a gmail sender has no organisation, so becomes a person without one
        assertEquals("new_unattributed", Db.first(conn, "SELECT method FROM mentions WHERE surface = 'Sam Jones'", rs -> rs.getString(1)));
    }

    @Test
    void copiesOfOneNumberAreOneDocumentWithVersions() throws Exception {
        EntityRow invoice = entitiesDao.findByTypeAndKey(conn, EntityType.DOCUMENT, "INV-8034");
        assertNotNull(invoice);
        assertEquals(2, ((List<?>) invoice.attrs.get("files")).size());
        assertEquals(List.of("INV-8034_v2"), invoice.attrs.get("versions"));
        assertEquals("invoice", invoice.attrs.get("doc_type"));
    }

    @Test
    void productIsNamedAfterItsLineItem() throws Exception {
        EntityRow product = entitiesDao.findByTypeAndKey(conn, EntityType.PRODUCT, "CS-110");
        assertNotNull(product);
        assertEquals("Case Sealer CS-110", product.name);
    }

    // ---------------------------------------------------------------- relate

    @Test
    void factStatedInTwoFilesIsOneRelationWithTwoEvidenceFiles() throws Exception {
        long relationId = relationId("INV-8034", "ISSUED_TO", "Acme Corporation");
        assertEquals(2, Db.number(conn, "SELECT weight FROM relations WHERE id = ?", relationId));
        assertEquals(2, Db.number(conn, "SELECT COUNT(*) FROM relation_evidence WHERE relation_id = ?", relationId));
    }

    @Test
    void relationsBetweenEntities() throws Exception {
        assertTrue(relationId("Thomas Bianchi", "WORKS_FOR", "Acme Corporation") > 0);
        assertTrue(relationId("Acme Corporation", "HAS_PROJECT", "JOB-2023-0003 Shrink Wrap Retrofit") > 0);
        assertTrue(relationId("JOB-2023-0003 Shrink Wrap Retrofit", "HAS_DOCUMENT", "INV-8034") > 0);
        assertTrue(relationId("INV-8034", "LISTS_PRODUCT", "Case Sealer CS-110") > 0);
    }

    @Test
    void derivedShortcutPersonInvolvedInProject() throws Exception {
        long relationId = relationId("Thomas Bianchi", "INVOLVED_IN", "JOB-2023-0003 Shrink Wrap Retrofit");
        assertEquals(1, Db.number(conn, "SELECT derived FROM relations WHERE id = ?", relationId));
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM relation_evidence WHERE relation_id = ?", relationId));
    }

    @Test
    void referencedDocumentWithoutFileIsMarkedMissing() throws Exception {
        EntityRow quote = entitiesDao.findByTypeAndKey(conn, EntityType.DOCUMENT, "QUO-9999");
        assertNotNull(quote);
        assertEquals(1, ((Number) quote.attrs.get("missing")).intValue());
        assertTrue(relationId("INV-8034", "REFERENCES", "QUO-9999") > 0);
    }

    @Test
    void documentLinkedToNothingIsPruned() throws Exception {
        assertEquals("orphan", Db.first(conn, "SELECT m.method FROM mentions m JOIN files f ON f.id = m.file_id"
                + " WHERE f.path = 'Admin/Notes/random_note.txt' AND m.role = 'self'", rs -> rs.getString(1)));
        assertNull(Db.first(conn, "SELECT id FROM entities WHERE name = 'random_note'", rs -> rs.getLong(1)));
    }

    /** Id of the relation "src REL dst" between the entities with these names, or 0. */
    private static long relationId(String src, String rel, String dst) throws Exception {
        return Db.number(conn, "SELECT r.id FROM relations r JOIN entities s ON s.id = r.src JOIN entities d ON d.id = r.dst"
                + " WHERE s.name = ? AND r.rel = ? AND d.name = ?", src, rel, dst);
    }
}
