package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.TestGraph;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.dataset.Profile;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.ingest.FileKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The general extractor: the offline rules, and how an answer (from Claude or the rules) becomes mentions and facts. */
class LlmParserTest {

    private static final Dataset HARBOR = new Dataset(new Owner("Harbor Robotics Inc", "harborrobotics.com"), Profile.defaults());

    private static final String REPLY =
            "Hi José,\n\nthe invoice for Order SO-88341 will follow this week. Mueller GmbH asked for a second cell.\n\n"
            + "Kind regards,\nDana Price\nSales Manager, Harbor Robotics Inc\n+1 510 555 0100\n";

    @Test
    void offlineRulesFindSignatureCompaniesAndNumbers() {
        Extraction ex = document("Mail/reply.eml");
        assertTrue(new LlmParser().parse(ex, row("Mail/reply.eml"), REPLY, null, null));

        // everything found offline has the role free_text and a lower confidence
        Extraction.Mention mueller = mention(ex, "Mueller GmbH");
        assertEquals(MentionRole.FREE_TEXT, mueller.role);
        assertEquals(LlmParser.RULES_CONFIDENCE, mueller.confidence);

        List<String> facts = facts(ex);
        assertTrue(facts.contains("reply MENTIONS Mueller GmbH"));
        assertTrue(facts.contains("reply MENTIONS Dana Price"));
        assertTrue(facts.contains("reply REFERENCES SO-88341"));
        // the signature's company is the owner: Dana works for it, but the owner itself is not MENTIONED
        assertTrue(facts.contains("Dana Price WORKS_FOR Harbor Robotics Inc"));
        assertFalse(facts.contains("reply MENTIONS Harbor Robotics Inc"));
        assertEquals("Sales Manager", mention(ex, "Dana Price").attrs.get("job_title"));
    }

    @Test
    void claudeAnswerBecomesMentionsAndFacts() {
        String answer = "{\"entities\": ["
                + "{\"type\": \"person\", \"name\": \"Lena Fischer\", \"email\": \"\", \"organisation\": \"Müller GmbH\", \"role\": \"Buyer\"},"
                + "{\"type\": \"company\", \"name\": \"Müller GmbH\", \"email\": \"\", \"organisation\": \"\", \"role\": \"\"},"
                + "{\"type\": \"project\", \"name\": \"Palletizer cell\", \"email\": \"\", \"organisation\": \"\", \"role\": \"\"},"
                + "{\"type\": \"spaceship\", \"name\": \"Nostromo\", \"email\": \"\", \"organisation\": \"\", \"role\": \"\"}],"
                + " \"relations\": [{\"src\": \"Lena Fischer\", \"rel\": \"ATTENTION_OF\", \"dst\": \"this document\"},"
                + " {\"src\": \"this document\", \"rel\": \"ISSUED_TO\", \"dst\": \"Müller GmbH\"},"
                + " {\"src\": \"Lena Fischer\", \"rel\": \"OWNS\", \"dst\": \"Müller GmbH\"}]}";
        Extraction ex = document("Mail/order.eml");
        LlmParser.addFindings(ex, LlmParser.fromJson(answer), MentionRole.LLM, LlmParser.LLM_CONFIDENCE, null);

        assertEquals(MentionRole.LLM, mention(ex, "Lena Fischer").role);
        assertEquals("Buyer", mention(ex, "Lena Fischer").attrs.get("job_title"));
        List<String> facts = facts(ex);
        assertTrue(facts.contains("Lena Fischer WORKS_FOR Müller GmbH"));
        assertTrue(facts.contains("order ISSUED_TO Müller GmbH"));
        assertTrue(facts.contains("Palletizer cell HAS_DOCUMENT order"));
        // an unknown entity type and an unknown relation are left out
        assertTrue(ex.mentions.stream().noneMatch(m -> m.surface.equals("Nostromo")));
        assertTrue(facts.stream().noneMatch(f -> f.contains("OWNS")));
    }

    @Test
    void nothingToReadWithoutADocument() {
        Extraction ex = new Extraction(1, HARBOR);
        assertFalse(new LlmParser().parse(ex, row("Mail/reply.eml"), REPLY, null, null));
        assertTrue(ex.mentions.isEmpty());
    }

    /** An extraction that already has the file's own document, as the Extractor gives it to the general extractor. */
    private static Extraction document(String path) {
        Extraction ex = new Extraction(1, HARBOR);
        ParserUtils.document(ex, row(path), "email", null, ParserUtils.stem(path));
        return ex;
    }

    private static FileRow row(String path) {
        return TestGraph.row(path, FileKind.EML, REPLY);
    }

    private static Extraction.Mention mention(Extraction ex, String surface) {
        for (Extraction.Mention m : ex.mentions) {
            if (m.surface.equals(surface) && m.etype != EntityType.DOCUMENT) {
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
