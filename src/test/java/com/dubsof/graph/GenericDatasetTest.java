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
import java.util.Arrays;
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
 *   <li>Sales/Invoices/HR-1040.pdf and HR-1043.pdf: invoices for the job "Conveyor Upgrade", billed first to
 *       "Kestral Foods Inc" (a typo), then to "Kestrel Foods Inc";</li>
 *   <li>Sales/Invoices/HR-1041.pdf: an invoice for the job "Clean Room Cell" to "Bayview Dental", whose
 *       e-mail signature later says "Bayview Dental Supplies Inc";</li>
 *   <li>Mail/: an order from José Müller (mueller-gmbh.de), a reply from Dana Price (harborrobotics.com,
 *       cc a gmail address) to "Jose Mueller", a follow-up mentioning "Mueller GmbH", and e-mails from
 *       Kestrel (kestrelfoods.com) and Bayview (bayviewdental.com);</li>
 *   <li>Mail/2024-04-05 floor plan.eml: "Jose Muller" writes from a second address at mueller-gmbh.de;</li>
 *   <li>Notes/: free-text call notes naming José Müller, Mueller GmbH and Dana Price, and a sentence
 *       starting "Ask Kestrel Foods Inc ...".</li>
 * </ul>
 * There is no customer or project folder and no profile.json.
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
        assertEquals(11, Db.number(conn, "SELECT COUNT(*) FROM files WHERE status = 'ok'"));
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
    void umlautSpelledWithoutTheEIsTheSamePerson() throws Exception {
        // "Jose Muller" (another address at Müller GmbH) is José Müller, not a new person
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'person' AND name = 'Jose Muller'"));
        assertEquals("José Müller", Db.first(conn, "SELECT e.name FROM mentions m JOIN entities e ON e.id = m.entity_id"
                + " WHERE m.etype = 'person' AND m.attrs LIKE '%j.muller@%'", rs -> rs.getString(1)));
    }

    @Test
    void sentenceStartIsNotPartOfACompanyName() throws Exception {
        // "Ask Kestrel Foods Inc whether ..." in the call notes
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'company' AND name LIKE 'Ask %'"));
        assertEquals("Kestrel Foods Inc", Db.first(conn, "SELECT e.name FROM mentions m JOIN entities e ON e.id = m.entity_id"
                + " WHERE m.surface = 'Ask Kestrel Foods Inc'", rs -> rs.getString(1)));
    }

    @Test
    void customerSpellingsAndDomainAreOneCompany() throws Exception {
        // "Müller GmbH" (bill-to, order), "Mueller GmbH" (follow-up, notes) and mueller-gmbh.de (senders)
        long companies = Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'company'"
                + " AND (name LIKE 'M%ller GmbH' OR name LIKE 'mueller%')");
        assertEquals(1, companies);
    }

    @Test
    void customerMisspelledFirstIsOneCompanyWithTheCommonName() throws Exception {
        // bill-to "Kestral Foods Inc" (HR-1040) comes before "Kestrel Foods Inc" (HR-1043, the e-mail signature)
        assertEquals(Arrays.asList("Kestrel Foods Inc"), companiesNamed("Kest%"));
    }

    @Test
    void customerDomainJoinsItsCompany() throws Exception {
        // kestrelfoods.com only matches the right spelling, not the "Kestral" one seen first
        assertEquals(Arrays.asList("Kestrel Foods Inc"), companiesNamed("kest%"));
        assertEquals("Kestrel Foods Inc", Db.first(conn, "SELECT e.name FROM mentions m JOIN entities e ON e.id = m.entity_id"
                + " WHERE m.role = 'email_domain' AND m.surface = 'kestrelfoods.com'", rs -> rs.getString(1)));
    }

    @Test
    void shortNameBeforeLongerNameIsOneCompany() throws Exception {
        // bill-to "Bayview Dental" (HR-1041) comes before "Bayview Dental Supplies Inc" (signature)
        assertEquals(1, companiesNamed("Bayview%").size(), "companies: " + companiesNamed("Bayview%"));
    }

    @Test
    void projectTitleInTwoFilesIsOneProjectWithoutAFolder() throws Exception {
        // "Job: Conveyor Upgrade" on HR-1040 and HR-1043
        List<Long> projects = Db.list(conn, "SELECT DISTINCT entity_id FROM mentions WHERE etype = 'project'"
                + " AND surface = 'Conveyor Upgrade'", rs -> rs.getObject(1) == null ? null : rs.getLong(1));
        assertEquals(1, projects.size());
        assertTrue(projects.get(0) != null, "resolved");
        assertEquals(1, Db.number(conn, "SELECT COUNT(*) FROM relations WHERE src = ? AND rel = 'HAS_DOCUMENT' AND dst = ?",
                projects.get(0), documentOf("Sales/Invoices/HR-1040.pdf")));
    }

    @Test
    void projectTitleInOneFileStaysUnresolved() throws Exception {
        // "Job: Clean Room Cell" is on HR-1041 only: too little to create a project from
        assertEquals(0, Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'project'"
                + " AND json_extract(attrs, '$.title') = 'Clean Room Cell'"));
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

    private static List<String> companiesNamed(String like) throws Exception {
        return Db.list(conn, "SELECT name FROM entities WHERE etype = 'company' AND name LIKE ? ORDER BY name", rs -> rs.getString(1), like);
    }

    private static String owner() throws Exception {
        return Db.first(conn, "SELECT name FROM entities WHERE json_extract(attrs, '$.role') = 'owner'", rs -> rs.getString(1));
    }

    private static Object invoiceAttr(String attr) throws Exception {
        return Db.first(conn, "SELECT json_extract(e.attrs, '$." + attr + "') FROM entities e"
                + " WHERE e.id = ?", rs -> rs.getObject(1), documentOf("Sales/Invoices/HR-1042.pdf"));
    }

    /** The document entity of a file (its "self" mention). */
    private static long documentOf(String path) throws Exception {
        return Db.number(conn, "SELECT m.entity_id FROM mentions m JOIN files f ON f.id = m.file_id"
                + " WHERE m.role = 'self' AND f.path = ?", path);
    }

    /** True when "src REL dst" exists between entities with these names (dst null: any target). */
    private static boolean relationExists(String src, String rel, String dst) throws Exception {
        return Db.number(conn, "SELECT COUNT(*) FROM relations r JOIN entities s ON s.id = r.src JOIN entities d ON d.id = r.dst"
                + " WHERE s.name = ? AND r.rel = ? AND (? IS NULL OR d.name = ?)", src, rel, dst, dst) > 0;
    }
}
