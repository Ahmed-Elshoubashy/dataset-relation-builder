package com.dubsof.graph.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.pipeline.Pipeline;
import com.dubsof.graph.read.OcrBackend;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chat on the generic fixture's graph: the fixed questions, and Claude's tool loop against a fake API. */
class ChatTest {

    @TempDir
    static Path work;
    static Connection conn;
    static long kestrel;

    @BeforeAll
    static void build() throws Exception {
        File dataset = Paths.get(ChatTest.class.getResource("/datasets/generic").toURI()).toFile();
        Pipeline.Options options = new Pipeline.Options();
        options.ocr = OcrBackend.NONE;
        options.serverOwner = null;
        Pipeline.Result result = Pipeline.build(dataset, work.resolve("graph.db").toFile(), options, (step, stage, detail) -> { });
        conn = Db.open(result.built, true);
        kestrel = Db.number(conn, "SELECT id FROM entities WHERE etype = 'company' AND name = 'Kestrel Foods Inc'");
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    // ---------------------------------------------------------------- the fixed questions (no API key)

    @Test
    void whatAreTheEntities() throws Exception {
        ChatAnswer a = preset("entities");
        long total = Db.number(conn, "SELECT COUNT(*) FROM entities");
        assertTrue(a.answer.startsWith("The graph has **" + total + "** entities of five kinds:"), a.answer);
        // no customer folders here: the customers are the companies invoices are issued to, not the owner
        assertTrue(a.answer.contains("companies (**3** customers): the owner, its customers and suppliers"), a.answer);
        assertTrue(a.answer.contains("|Harbor Robotics Inc]]."), a.answer);
        assertEquals("preset", a.engine);
    }

    @Test
    void howManyCustomers() throws Exception {
        ChatAnswer a = preset("customers");
        assertTrue(a.answer.startsWith("The dataset has **3** customers:"), a.answer);
        assertTrue(a.answer.contains("[[" + kestrel + "|Kestrel Foods Inc]]"), a.answer);
        assertTrue(a.entities.containsKey(kestrel), "links carry the entity's type");
    }

    @Test
    void howManyPeople() throws Exception {
        long people = Db.number(conn, "SELECT COUNT(*) FROM entities WHERE etype = 'person'");
        assertEquals("The dataset has **" + people + "** people.", preset("people").answer);
    }

    @Test
    void whoIsTheOwner() throws Exception {
        ChatAnswer a = preset("owner");
        assertTrue(a.answer.contains("|Harbor Robotics Inc]] (harborrobotics.com): the organisation whose files these are"), a.answer);
        assertNotNull(a.focus, "the graph centres on the owner");
    }

    @Test
    void onlyTheFixedQuestionsWorkWithoutAKey() throws Exception {
        ChatException unknown = assertThrows(ChatException.class, () -> new ChatService(conn, (AnthropicClient) null).preset("weather"));
        assertEquals(400, unknown.status);
        ChatException typed = assertThrows(ChatException.class,
                () -> new ChatService(conn, (AnthropicClient) null).ask(question("How many customers are there?")));
        assertEquals(400, typed.status);
        assertTrue(typed.getMessage().contains("needs Claude"), typed.getMessage());
    }

    // ---------------------------------------------------------------- Claude, against a fake API

    @Test
    void claudeCallsToolsThenAnswers() throws Exception {
        List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        String toolUse = "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"find_entities\",\"input\":{\"customers_only\":true}}";
        String text = "{\"type\":\"text\",\"text\":\"There are **3** customers, e.g. [[" + kestrel + "|Kestrel Foods Inc]].\"}";
        HttpServer api = fakeApi(requests, message(toolUse, "tool_use"), message(text, "end_turn"));
        try {
            ChatAnswer a = new ChatService(conn, client(api)).ask(question("how many customers are there"));

            assertEquals("claude", a.engine);
            assertEquals("There are **3** customers, e.g. [[" + kestrel + "|Kestrel Foods Inc]].", a.answer);
            assertEquals("find_entities", a.tools.get(0).get("name"));
            assertTrue(a.entities.containsKey(kestrel));
            // the second request carries the tool's result back to Claude
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("\"tool_use_id\":\"tu_1\""), requests.get(1));
            assertTrue(requests.get(1).contains("\\\"total\\\":3"), requests.get(1));
            // and every request offers the tools
            assertTrue(requests.get(0).contains("\"name\":\"list_related\""));
        } finally {
            api.stop(0);
        }
    }

    @Test
    void aRejectedKeyIsReported() throws Exception {
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        try {
            ChatException e = assertThrows(ChatException.class,
                    () -> new ChatService(conn, client(api)).ask(question("How many customers does the dataset have?")));
            assertEquals(400, e.status);
            assertTrue(e.getMessage().contains("rejected the API key"), e.getMessage());
        } finally {
            api.stop(0);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ChatAnswer preset(String id) throws Exception {
        return new ChatService(conn, (AnthropicClient) null).preset(id);
    }

    private static List<Map<String, Object>> question(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", text);
        return Collections.singletonList(message);
    }

    private static String message(String block, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\",\"content\":[" + block + "],"
                + "\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    }

    /** A fake Messages API that records each request body and answers with the next response in order. */
    private static HttpServer fakeApi(List<String> requests, String... responses) throws Exception {
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses[Math.min(requests.size(), responses.length) - 1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        api.start();
        return api;
    }

    private static AnthropicClient client(HttpServer api) {
        return AnthropicOkHttpClient.builder().baseUrl("http://127.0.0.1:" + api.getAddress().getPort())
                .apiKey("sk-test").maxRetries(0).build();
    }
}
