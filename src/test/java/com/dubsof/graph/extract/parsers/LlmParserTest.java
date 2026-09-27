package com.dubsof.graph.extract.parsers;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.dubsof.graph.TestGraph;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.dataset.Profile;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.ingest.FileKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void failedClaudeCallsFallBackToTheRulesAndAreReported() throws Exception {
        // a fake Anthropic API that rejects every key
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"
                    .getBytes("UTF-8");
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        AnthropicClient client = AnthropicOkHttpClient.builder().baseUrl("http://127.0.0.1:" + api.getAddress().getPort())
                .apiKey("sk-bad").maxRetries(0).build();
        try (LlmParser parser = new LlmParser(client)) {
            FileRow row = row("Mail/reply.eml");
            parser.prepare(Arrays.asList(row), Arrays.asList(REPLY));
            Extraction ex = document("Mail/reply.eml");
            assertTrue(parser.parse(ex, row, REPLY, null, null));

            // the rules read the file, and the failed file is not asked about again
            assertEquals(MentionRole.FREE_TEXT, mention(ex, "Dana Price").role);
            assertEquals(Integer.valueOf(1), parser.claudeSummary().get("free_text_claude_calls"));
            assertEquals(Integer.valueOf(1), parser.claudeSummary().get("free_text_claude_failed_auth"));
            assertTrue(parser.claudeProblem().startsWith("Claude extraction failed for all 1 files: UnauthorizedException"),
                    parser.claudeProblem());
        } finally {
            api.stop(0);
        }
    }

    @Test
    void offlineReportsNothing() throws Exception {
        try (LlmParser parser = new LlmParser()) {
            assertTrue(parser.claudeSummary().isEmpty());
            assertNull(parser.claudeProblem());
        }
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
