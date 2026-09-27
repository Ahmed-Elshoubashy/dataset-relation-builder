package com.dubsof.graph.extract;

import com.dubsof.graph.TestGraph;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.ingest.FileKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One file in, mentions and facts out: the INV-8034 walk-through from the docs, and the filename fallbacks. */
class ExtractorTest {

    private static final String INV_8034 =
            "Meridian Packaging Systems Ltd\n"
            + "INVOICE\n"
            + "No: INV-8034\n"
            + "Date: 11 Mar 2024\n"
            + "Job: Shrink Wrap Retrofit\n"
            + "BILL TO:\n"
            + "ACME Corp\n"
            + "240 Priory Lane\n"
            + "Wolverhampton\n"
            + "E5 3SO\n"
            + "Attn: Thomas Bianchi\n"
            + "Description Qty Unit Unit Price Line Total\n"
            + "PLC Control Panel (Siemens S7 based) 1 unit £11,327.92 £11,327.92\n"
            + "Subtotal: £11,327.92\n"
            + "TOTAL: £11,327.92\n";

    private final Extractor extractor = new Extractor();

    @Test
    void invoiceInAJobFolder() {
        FileRow row = TestGraph.row("Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/Invoices/"
                + "INV-8034_Acme Corporation.pdf", FileKind.PDF, INV_8034);
        Extraction ex = extractor.extractFile(row);

        assertEquals(List.of(
                "company Acme Corporation (folder)",
                "project Shrink Wrap Retrofit (folder)",
                "document INV-8034 (self)",
                "company ACME Corp (bill_to)",
                "person Thomas Bianchi (attn)",
                "project Shrink Wrap Retrofit (doc_job_field)",
                "company Acme Corporation (filename)"), mentions(ex));

        assertEquals(List.of(
                "Acme Corporation HAS_PROJECT Shrink Wrap Retrofit",
                "INV-8034 ISSUED_TO ACME Corp",
                "Thomas Bianchi WORKS_FOR ACME Corp",
                "INV-8034 ATTENTION_OF Thomas Bianchi",
                "Shrink Wrap Retrofit HAS_DOCUMENT INV-8034",
                "INV-8034 ISSUED_TO Acme Corporation",
                "Shrink Wrap Retrofit HAS_DOCUMENT INV-8034"), facts(ex));

        assertEquals(2, ex.doc);
        assertEquals("invoice", ex.docMention().attrs.get("doc_type"));
        assertEquals("JOB-2023-0003", ex.mentions.get(1).attrs.get("job_id"));
        assertEquals(0.7, ex.mentions.get(6).confidence);
        assertEquals(Boolean.TRUE, ex.mentions.get(6).attrs.get("truncated"));
    }

    @Test
    void unreadFileGetsItsDocumentFromTheFileName() {
        FileRow scan = TestGraph.row("Customers/Acme Corporation/Scans/INV-8099_Acme Corporation.png", FileKind.PNG, null);
        Extraction ex = extractor.extractFile(scan);

        Extraction.Mention document = ex.docMention();
        assertEquals("INV-8099", document.surface);
        assertEquals("invoice", document.attrs.get("doc_type"));
        assertEquals(Boolean.TRUE, document.attrs.get("unread"));
        assertTrue(facts(ex).contains("INV-8099 ISSUED_TO Acme Corporation"));
        // no JOB folder: the document is filed under the customer instead
        assertTrue(facts(ex).contains("INV-8099 FILED_UNDER Acme Corporation"));
    }

    @Test
    void productCodeInTheFileName() {
        FileRow datasheet = TestGraph.row("Engineering/Datasheets/GB-40_Datasheet.pdf", FileKind.PDF, null);
        Extraction ex = extractor.extractFile(datasheet);
        assertTrue(facts(ex).contains("GB-40_Datasheet DESCRIBES GB-40"));
    }

    @Test
    void blankUnrecognisedFileHasNoDocument() {
        FileRow blank = TestGraph.row("Admin/Notes/empty.txt", FileKind.TEXT, "   ");
        Extraction ex = extractor.extractFile(blank);
        assertNull(ex.doc);
        assertTrue(ex.facts.isEmpty());
    }

    @Test
    void referencesInTheTextButNotToItself() {
        // no parser recognises this note, so its document is QUO-5238, from the file name
        FileRow note = TestGraph.row("Admin/Notes/QUO-5238.txt", FileKind.TEXT, "Follow-up on QUO-5238 and DN-6041.");
        Extraction ex = extractor.extractFile(note);
        List<String> facts = facts(ex);
        assertEquals("QUO-5238", ex.docMention().surface);
        assertTrue(facts.contains("QUO-5238 REFERENCES DN-6041"));
        assertTrue(!facts.contains("QUO-5238 REFERENCES QUO-5238"));
    }

    private static List<String> mentions(Extraction ex) {
        List<String> out = new ArrayList<>();
        for (Extraction.Mention m : ex.mentions) {
            out.add(m.etype.value() + " " + m.surface + " (" + m.role.value() + ")");
        }
        return out;
    }

    private static List<String> facts(Extraction ex) {
        List<String> out = new ArrayList<>();
        for (Extraction.Fact f : ex.facts) {
            out.add(ex.mentions.get(f.src).surface + " " + f.rel.value() + " " + ex.mentions.get(f.dst).surface);
        }
        return out;
    }
}
