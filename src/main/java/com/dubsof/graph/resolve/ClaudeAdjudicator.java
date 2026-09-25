package com.dubsof.graph.resolve;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.dubsof.graph.Config;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Asks Claude whether two borderline names are the same organisation. Answers are cached. */
public class ClaudeAdjudicator implements Adjudicator {

    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    private final Connection cache;

    public ClaudeAdjudicator() throws Exception {
        cache = Db.open(Config.OCR_CACHE_FILE, true);
        Db.update(cache, "CREATE TABLE IF NOT EXISTS adjudications (k TEXT PRIMARY KEY, v TEXT)");
    }

    public Verdict sameEntity(String etype, String mention, String candidate, String context) {
        try {
            String key = Json.write(Arrays.asList(etype, mention, candidate));
            String cached = (String) Db.scalar(cache, "SELECT v FROM adjudications WHERE k=?", key);
            if (cached != null) {
                return toVerdict(Json.readMap(cached));
            }
            String question = "In a UK packaging-machinery supplier's business files, does the " + etype + " name '"
                    + mention + "' refer to the same real-world " + etype + " as '" + candidate + "'? Context: " + context
                    + ". Consider abbreviations, typos, spacing and legal suffixes; answer false if they could "
                    + "plausibly be different organisations.";
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Config.CLAUDE_MODEL)
                    .maxTokens(2000L)
                    .putAdditionalBodyProperty("output_config", JsonValue.from(outputConfig()))
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage(question)
                    .build();
            Message response = client.messages().create(params);
            if (response.stopReason().isPresent() && response.stopReason().get().equals(StopReason.REFUSAL)) {
                return new Verdict(false, 0, "model declined");
            }
            StringBuilder text = new StringBuilder();
            for (ContentBlock b : response.content()) {
                if (b.isText()) {
                    text.append(b.asText().text());
                }
            }
            Db.update(cache, "INSERT OR REPLACE INTO adjudications VALUES (?,?)", key, text.toString());
            return toVerdict(Json.readMap(text.toString()));
        } catch (Exception e) {
            return new Verdict(false, 0, "adjudicator error: " + e.getMessage());
        }
    }

    /** Low effort, and the answer must match a small JSON schema. */
    private static Map<String, Object> outputConfig() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("same", type("boolean"));
        props.put("confidence", type("number"));
        props.put("reason", type("string"));
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", Arrays.asList("same", "confidence", "reason"));
        schema.put("additionalProperties", false);
        Map<String, Object> format = new LinkedHashMap<String, Object>();
        format.put("type", "json_schema");
        format.put("schema", schema);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("effort", "low");
        config.put("format", format);
        return config;
    }

    private static Map<String, Object> type(String t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", t);
        return m;
    }

    private static Verdict toVerdict(Map<String, Object> v) {
        return new Verdict(Boolean.TRUE.equals(v.get("same")), ((Number) v.get("confidence")).doubleValue(), String.valueOf(v.get("reason")));
    }

    /** Claude when configured and a key is available, the rules otherwise. */
    public static Adjudicator createDefault() {
        if ("claude".equals(Config.ADJUDICATOR) && Config.apiKeyFromEnv() != null) {
            try {
                return new ClaudeAdjudicator();
            } catch (Exception e) {
                return new RuleAdjudicator();
            }
        }
        return new RuleAdjudicator();
    }
}
