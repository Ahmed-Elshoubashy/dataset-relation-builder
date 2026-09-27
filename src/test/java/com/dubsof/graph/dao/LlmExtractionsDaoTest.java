package com.dubsof.graph.dao;

import com.dubsof.graph.db.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The general extractor's answer cache: keyed by content, model and prompt version. */
class LlmExtractionsDaoTest {

    private final LlmExtractionsDao dao = new LlmExtractionsDao();

    @Test
    void answersToAnotherPromptVersionAreNotReused(@TempDir Path dir) throws Exception {
        try (Connection conn = Db.open(dir.resolve("cache.db").toFile(), true)) {
            dao.createTable(conn);
            dao.save(conn, "abc", "claude-x", 1, "{\"entities\": []}");
            assertEquals("{\"entities\": []}", dao.findJson(conn, "abc", "claude-x", 1));
            assertNull(dao.findJson(conn, "abc", "claude-x", 2));
            assertNull(dao.findJson(conn, "abc", "claude-y", 1));
        }
    }

    @Test
    void aCacheFromBeforePromptVersionsIsStartedAgain(@TempDir Path dir) throws Exception {
        try (Connection conn = Db.open(dir.resolve("cache.db").toFile(), true)) {
            Db.update(conn, "CREATE TABLE llm_extractions (sha256 TEXT, model TEXT, json TEXT, PRIMARY KEY (sha256, model))");
            Db.update(conn, "INSERT INTO llm_extractions VALUES ('abc', 'claude-x', '{}')");
            dao.createTable(conn);
            assertNull(dao.findJson(conn, "abc", "claude-x", 1));
            dao.save(conn, "abc", "claude-x", 1, "{}");
            assertEquals("{}", dao.findJson(conn, "abc", "claude-x", 1));
        }
    }
}
