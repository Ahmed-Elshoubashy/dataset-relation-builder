package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.TestGraph;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.ingest.FileKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each template parser: recognises its own template, and adds nothing to anything else. */
class ParsersTest {

    private static final Parser[] ALL = {
        new EmailParser(), new VcardParser(), new CalendarParser(), new BusinessDocParser(), new DrawingParser(),
        new CalibrationParser(), new SpecParser(), new ManualParser(), new CertificateParser(), new LetterParser(),
        new ContractParser(), new ReportParser(), new ServiceReportParser(), new MeetingNotesParser(),
        new ItemListParser(), new ScreenshotTableParser(),
    };

    @Test
    void noParserRecognisesPlainText() {
        FileRow row = TestGraph.row("Admin/Notes/todo.txt", FileKind.TEXT, "Remember to call the supplier on Monday.");
        for (Parser parser : ALL) {
            Extraction ex = new Extraction(1);
            assertFalse(parser.parse(ex, row, row.text, null, null), parser.getClass().getSimpleName());
            assertTrue(ex.mentions.isEmpty(), parser.getClass().getSimpleName() + " must add nothing when it says no");
        }
    }

    @Test
    void businessDocument() {
        String text = "INVOICE\nNo: INV-8034\nDate: 11 Mar 2024\nBILL TO:\nACME Corp\n240 Priory Lane\nAttn: Thomas Bianchi\n"
                + "Description Qty Unit Unit Price Line Total\n"
                + "Case Sealer CS-110 2 unit £1,000.00 £2,000.00\n"
                + "Subtotal: £2,000.00\nTOTAL: £2,400.00\n";
        Extraction ex = parse(new BusinessDocParser(), "Customers/Acme Corporation/Invoices/INV-8034.pdf", FileKind.PDF, text);
        assertEquals("INV-8034", ex.docMention().attrs.get("key"));
        assertEquals(2400.0, ex.docMention().attrs.get("total"));
        assertEquals(2000.0, ex.docMention().attrs.get("subtotal"));
        assertEquals(1, ((List<?>) ex.docMention().attrs.get("line_items")).size());
        assertEquals("240 Priory Lane", mention(ex, "ACME Corp").attrs.get("address"));
        List<String> facts = facts(ex);
        assertTrue(facts.contains("INV-8034 ISSUED_TO ACME Corp"));
        assertTrue(facts.contains("INV-8034 ATTENTION_OF Thomas Bianchi"));
        assertTrue(facts.contains("INV-8034 LISTS_PRODUCT Case Sealer CS-110"));
    }

    @Test
    void emailSendersAndRecipients() {
        String text = "From: Sam Jones <sam.jones@acmecorp.com>\nTo: Thomas Bianchi <thomas.bianchi@meridianpackaging.co.uk>\n"
                + "Cc: Grace Hart <grace.hart@acmecorp.com>\nSubject: Fwd: Shrink Wrap Retrofit site visit\n\nSee you there.";
        Extraction ex = parse(new EmailParser(), "Admin/Mail/visit.eml", FileKind.EML, text);
        List<String> facts = facts(ex);
        assertTrue(facts.contains("Sam Jones SENT Fwd: Shrink Wrap Retrofit site visit"));
        assertTrue(facts.contains("Thomas Bianchi RECEIVED Fwd: Shrink Wrap Retrofit site visit"));
        assertTrue(facts.contains("Grace Hart RECEIVED Fwd: Shrink Wrap Retrofit site visit"));
        assertTrue(facts.contains("Sam Jones WORKS_FOR acmecorp.com"));
        assertTrue(facts.contains("Shrink Wrap Retrofit HAS_DOCUMENT Fwd: Shrink Wrap Retrofit site visit"));
    }

    @Test
    void emailParserOnlyReadsEmailFiles() {
        String text = "From: Sam Jones <sam.jones@acmecorp.com>\nSubject: hello\n\nbody";
        Extraction ex = new Extraction(1);
        FileRow notEmail = TestGraph.row("Admin/Mail/visit.txt", FileKind.TEXT, text);
        assertFalse(new EmailParser().parse(ex, notEmail, text, null, null));
    }

    @Test
    void letterRecipientProjectAndSignatory() {
        String text = "Meridian Packaging Systems Ltd\n12 Mar 2024\nAcme Corporation\n240 Priory Lane\n"
                + "Re: Site access · Shrink Wrap Retrofit\nDear Thomas,\nPlease confirm the dates.\nKind regards,\n"
                + "Jane Smith\nProject Manager, Meridian Packaging Systems Ltd\n";
        Extraction ex = parse(new LetterParser(), "Customers/Acme Corporation/Letters/letter_014.docx", FileKind.DOCX, text);
        assertEquals("LETTER-014", ex.docMention().attrs.get("key"));
        List<String> facts = facts(ex);
        assertTrue(facts.contains("LETTER-014 ADDRESSED_TO Acme Corporation"));
        assertTrue(facts.contains("Shrink Wrap Retrofit HAS_DOCUMENT LETTER-014"));
        assertTrue(facts.contains("Jane Smith AUTHORED LETTER-014"));
        assertTrue(facts.contains("Jane Smith WORKS_FOR Meridian Packaging Systems Ltd"));   // owner, from the company line
        assertEquals("Project Manager", mention(ex, "Jane Smith").attrs.get("job_title"));
    }

    @Test
    void drawingCustomerAndAuthor() {
        String text = "Drawing No: DWG-9296\nTitle: Conveyor Guard Assembly\nRev: B\nCustomer: Acme Corporation\n"
                + "Job: Shrink Wrap Retrofit\nDrawn By: Jane Smith\n";
        Extraction ex = parse(new DrawingParser(), "Engineering/Drawings/DWG-9296.pdf", FileKind.PDF, text);
        assertEquals("DWG-9296", ex.docMention().attrs.get("key"));
        assertEquals("B", ex.docMention().attrs.get("revision"));
        List<String> facts = facts(ex);
        assertTrue(facts.contains("Conveyor Guard Assembly ISSUED_TO Acme Corporation"));
        assertTrue(facts.contains("Jane Smith AUTHORED Conveyor Guard Assembly"));
        assertTrue(facts.contains("Shrink Wrap Retrofit HAS_DOCUMENT Conveyor Guard Assembly"));
    }

    @Test
    void screenshotRowsBecomeProjectsOfTheirCustomers() {
        String text = "Customer Portal Dashboard\nJob Code | Customer | Status | Value\n"
                + "JOB-2023-0579 | Kingsley Textiles Ltd | Awaiting Parts | £35,235\n"
                + "JOB-2025-0645 | Blenheim Foods Group | In Progress | £45,523\n";
        Extraction ex = parse(new ScreenshotTableParser(), "Admin/Scans/site_photo.png", FileKind.PNG, text);
        List<String> facts = facts(ex);
        assertTrue(facts.contains("Kingsley Textiles Ltd HAS_PROJECT JOB-2023-0579"));
        assertTrue(facts.contains("Blenheim Foods Group HAS_PROJECT JOB-2025-0645"));
        assertEquals("Awaiting Parts", mention(ex, "JOB-2023-0579").attrs.get("status"));
    }

    private static Extraction parse(Parser parser, String path, FileKind kind, String text) {
        Extraction ex = new Extraction(1);
        FileRow row = TestGraph.row(path, kind, text);
        assertTrue(parser.parse(ex, row, text, null, null), parser.getClass().getSimpleName() + " should recognise its template");
        return ex;
    }

    private static Extraction.Mention mention(Extraction ex, String surface) {
        for (Extraction.Mention m : ex.mentions) {
            if (m.surface.equals(surface)) {
                return m;
            }
        }
        throw new AssertionError("no mention " + surface);
    }

    private static List<String> facts(Extraction ex) {
        List<String> out = new ArrayList<>();
        for (Extraction.Fact f : ex.facts) {
            out.add(ex.mentions.get(f.src).surface + " " + f.rel.value() + " " + ex.mentions.get(f.dst).surface);
        }
        return out;
    }
}
