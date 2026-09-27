package com.dubsof.graph.extract.parsers;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.dubsof.graph.Config;
import com.dubsof.graph.dao.LlmExtractionsDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.resolve.NameMatcher;
import com.dubsof.graph.util.Json;
import com.dubsof.graph.util.Text;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dubsof.graph.extract.parsers.ParserUtils.PREFIX_TYPES;
import static com.dubsof.graph.extract.parsers.ParserUtils.cleanPerson;
import static com.dubsof.graph.extract.parsers.ParserUtils.companyFromDomain;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;

/**
 * The general extractor, tried after the template parsers: it reads files no template recognised, and the
 * free text of those that are mostly prose (e-mail bodies, letters, meeting notes), and links the file's
 * document to every company, person, project, document and product the text names.
 *
 * With an API key it asks Claude for them as JSON (answers cached by file content in ocr_cache.db). Without
 * one it uses FreeTextRules, which finds less but works offline. Mentions get the role "llm" or "free_text"
 * and a lower confidence than template fields, so the resolver and the UI can tell them apart.
 */
public class LlmParser implements Parser {

    static final double LLM_CONFIDENCE = 0.7;
    static final double RULES_CONFIDENCE = 0.6;
    /** Longest text sent to Claude; the start of a business document holds the parties and references. */
    private static final int MAX_CHARS = 12000;

    /** Relations Claude may use: the ones a document can state (the derived ones are computed later). */
    private static final List<RelationType> ALLOWED_RELATIONS = Arrays.asList(
            RelationType.WORKS_FOR, RelationType.ISSUED_TO, RelationType.ADDRESSED_TO, RelationType.ATTENTION_OF,
            RelationType.AUTHORED, RelationType.SENT, RelationType.RECEIVED, RelationType.ATTENDED,
            RelationType.PARTY_TO, RelationType.HOLDS, RelationType.ISSUED_BY, RelationType.HAS_PROJECT,
            RelationType.HAS_DOCUMENT, RelationType.LISTS_PRODUCT, RelationType.DESCRIBES, RelationType.REFERENCES,
            RelationType.MENTIONS);

    private final AnthropicClient client;       // null: offline, rules only
    private Connection cache;                    // ocr_cache.db, opened on first use
    private final LlmExtractionsDao llmExtractionsDao = new LlmExtractionsDao();
    /** Answers fetched by prepare(), by file sha256. */
    private final Map<String, String> answers = new ConcurrentHashMap<>();

    /** Offline: the rules only. */
    public LlmParser() {
        this.client = null;
    }

    /** With a key, Claude reads the files; with null, the rules do. */
    public LlmParser(String apiKey) {
        this.client = apiKey == null ? null
                : AnthropicOkHttpClient.builder().fromEnv().maxRetries(4).apiKey(apiKey).build();
    }

    public boolean usesClaude() {
        return client != null;
    }

    /**
     * Asks Claude about many files at once (in parallel, like OCR), so parse() only reads the answers.
     * Files already in the cache cost nothing. Does nothing offline.
     */
    public void prepare(List<FileRow> rows, List<String> texts) throws Exception {
        if (client == null) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Config.OCR_WORKERS);
        try {
            for (int i = 0; i < rows.size(); i++) {
                final String sha = rows.get(i).sha256;
                final String text = texts.get(i);
                if (answers.containsKey(sha) || cached(sha) != null) {
                    continue;
                }
                pool.submit(new Runnable() {
                    public void run() {
                        String json = askClaude(text);
                        if (json != null) {
                            answers.put(sha, json);
                            save(sha, json);
                        }
                    }
                });
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.DAYS);
        }
    }

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        if (ex.doc == null || Text.isBlank(text)) {
            return false;
        }
        TextFindings found = null;
        MentionRole role = MentionRole.FREE_TEXT;
        double confidence = RULES_CONFIDENCE;
        if (client != null) {
            String json = answers.containsKey(row.sha256) ? answers.get(row.sha256) : cached(row.sha256);
            if (json == null) {
                json = askClaude(text);
                if (json != null) {
                    save(row.sha256, json);
                }
            }
            if (json != null) {
                found = fromJson(json);
                role = MentionRole.LLM;
                confidence = LLM_CONFIDENCE;
            }
        }
        if (found == null) {   // offline, or Claude could not answer
            found = FreeTextRules.find(text, ex.dataset.names);
        }
        addFindings(ex, found, role, confidence, folderCompany);
        return true;
    }

    // ------------------------------------------------------------------ findings -> mentions and facts

    /**
     * Each entity becomes a mention linked to this file's document: companies, people and products by
     * MENTIONS, documents by REFERENCES, projects by HAS_DOCUMENT. People get their organisation (named, or
     * from their e-mail domain). The owner is never MENTIONED: its name is on everything it wrote.
     */
    static void addFindings(Extraction ex, TextFindings found, MentionRole role, double confidence, Integer folderCompany) {
        NameMatcher names = ex.dataset.names;
        Owner owner = ex.dataset.owner;
        String ownerKey = owner.isKnown() ? names.companyKey(owner.name) : null;
        Map<String, Integer> mentionByName = new HashMap<>();
        mentionByName.put(TextFindings.THIS_DOCUMENT, ex.doc);

        for (TextFindings.Found entity : found.entities) {
            Integer mention = null;
            if (entity.type == EntityType.COMPANY) {
                mention = company(ex, entity.name, role, confidence, ownerKey);
                if (!isOwner(names, entity.name, ownerKey)) {
                    ex.fact(ex.doc, RelationType.MENTIONS, mention);
                }
            } else if (entity.type == EntityType.PERSON) {
                String name = cleanPerson(entity.name);
                if (name == null) {
                    continue;
                }
                Integer organisation = entity.organisation != null ? company(ex, entity.organisation, role, confidence, ownerKey)
                        : entity.email != null ? companyFromDomain(ex, entity.email) : null;
                mention = ex.addMentionWithConfidence(EntityType.PERSON, name, role, confidence, "org_mention", organisation,
                        "email", entity.email == null ? null : entity.email.toLowerCase(), "job_title", entity.jobTitle);
                ex.fact(mention, RelationType.WORKS_FOR, organisation);
                ex.fact(ex.doc, RelationType.MENTIONS, mention);
            } else if (entity.type == EntityType.DOCUMENT) {
                if (isThisOrKnownDocument(ex, entity.name)) {
                    continue;
                }
                String prefix = entity.name.contains("-") ? entity.name.substring(0, entity.name.indexOf('-')) : null;
                mention = ex.addMentionWithConfidence(EntityType.DOCUMENT, entity.name, role, confidence,
                        "key", entity.name, "doc_type", prefix == null ? null : PREFIX_TYPES.get(prefix));
                ex.fact(ex.doc, RelationType.REFERENCES, mention);
            } else if (entity.type == EntityType.PROJECT) {
                mention = ex.addMentionWithConfidence(EntityType.PROJECT, entity.name, role, confidence, "company_mention", folderCompany);
                ex.fact(mention, RelationType.HAS_DOCUMENT, ex.doc);
            } else if (entity.type == EntityType.PRODUCT) {
                mention = ex.addMentionWithConfidence(EntityType.PRODUCT, entity.name, role, confidence);
                ex.fact(ex.doc, RelationType.MENTIONS, mention);
            }
            if (mention != null) {
                mentionByName.put(entity.name, mention);
            }
        }
        for (TextFindings.FoundRelation relation : found.relations) {
            ex.fact(mentionByName.get(relation.src), relation.rel, mentionByName.get(relation.dst));
        }
    }

    /** A company mention; the owner is its usual owner mention, so it merges with the owner's anchor. */
    private static Integer company(Extraction ex, String name, MentionRole role, double confidence, String ownerKey) {
        if (isOwner(ex.dataset.names, name, ownerKey)) {
            return owner(ex);
        }
        return ex.addMentionWithConfidence(EntityType.COMPANY, name, role, confidence);
    }

    private static boolean isOwner(NameMatcher names, String name, String ownerKey) {
        return ownerKey != null && names.companyKey(name).equals(ownerKey);
    }

    /** The file's own document, or a document the templates already found in this file. */
    private static boolean isThisOrKnownDocument(Extraction ex, String number) {
        for (Extraction.Mention m : ex.mentions) {
            if (m.etype == EntityType.DOCUMENT && (m.surface.equals(number) || number.equals(m.attrs.get("key")))) {
                return true;
            }
        }
        return false;
    }

    /** Claude's JSON answer as findings; unknown types and relations are left out. */
    @SuppressWarnings("unchecked")
    static TextFindings fromJson(String json) {
        TextFindings found = new TextFindings();
        Map<String, Object> answer = Json.readMap(json);
        for (Object item : (List<Object>) answer.get("entities")) {
            Map<String, Object> e = (Map<String, Object>) item;
            EntityType type;
            try {
                type = EntityType.fromValue(String.valueOf(e.get("type")));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            String name = blankToNull(e.get("name"));
            if (name != null) {
                found.add(type, name, blankToNull(e.get("email")), blankToNull(e.get("organisation")), blankToNull(e.get("role")));
            }
        }
        for (Object item : (List<Object>) answer.get("relations")) {
            Map<String, Object> r = (Map<String, Object>) item;
            try {
                RelationType rel = RelationType.fromValue(String.valueOf(r.get("rel")));
                found.relations.add(new TextFindings.FoundRelation(String.valueOf(r.get("src")), rel, String.valueOf(r.get("dst"))));
            } catch (IllegalArgumentException unknown) {
                // not one of our relation types
            }
        }
        return found;
    }

    private static String blankToNull(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim();
    }

    // ------------------------------------------------------------------ Claude and the cache

    /** Claude's JSON answer for a text, or null when the call fails (the rules are used instead). */
    private String askClaude(String text) {
        try {
            String question = "List the organisations, people, projects, documents (by their number) and products that "
                    + "this business document names, and how they relate. Use \"" + TextFindings.THIS_DOCUMENT
                    + "\" for the document itself. For a person give their e-mail, organisation and job title when "
                    + "the text says them, otherwise an empty string. Only list what the text states.\n\n"
                    + Text.truncate(text, MAX_CHARS);
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Config.CLAUDE_MODEL)
                    .maxTokens(4000L)
                    .putAdditionalBodyProperty("output_config", JsonValue.from(outputConfig()))
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage(question)
                    .build();
            Message response = client.messages().create(params);
            StringBuilder json = new StringBuilder();
            for (ContentBlock block : response.content()) {
                if (block.isText()) {
                    json.append(block.asText().text());
                }
            }
            fromJson(json.toString());   // check it parses before keeping it
            return json.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Low effort, and the answer must match a JSON schema limited to our entity and relation types. */
    private static Map<String, Object> outputConfig() {
        List<String> entityTypes = new ArrayList<>();
        for (EntityType t : EntityType.values()) {
            entityTypes.add(t.value());
        }
        List<String> relationTypes = new ArrayList<>();
        for (RelationType r : ALLOWED_RELATIONS) {
            relationTypes.add(r.value());
        }
        Map<String, Object> entity = object(Arrays.asList("type", "name", "email", "organisation", "role"),
                property("type", enumOf(entityTypes)), property("name", string()), property("email", string()),
                property("organisation", string()), property("role", string()));
        Map<String, Object> relation = object(Arrays.asList("src", "rel", "dst"),
                property("src", string()), property("rel", enumOf(relationTypes)), property("dst", string()));
        Map<String, Object> schema = object(Arrays.asList("entities", "relations"),
                property("entities", arrayOf(entity)), property("relations", arrayOf(relation)));
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("schema", schema);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("effort", "low");
        config.put("format", format);
        return config;
    }

    @SafeVarargs
    private static Map<String, Object> object(List<String> required, Map.Entry<String, Object>... properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> p : properties) {
            props.put(p.getKey(), p.getValue());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map.Entry<String, Object> property(String name, Object schema) {
        return new java.util.AbstractMap.SimpleEntry<>(name, schema);
    }

    private static Map<String, Object> string() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        return m;
    }

    private static Map<String, Object> enumOf(List<String> values) {
        Map<String, Object> m = string();
        m.put("enum", values);
        return m;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        return m;
    }

    private synchronized String cached(String sha) {
        try {
            return llmExtractionsDao.findJson(cache(), sha, Config.CLAUDE_MODEL);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void save(String sha, String json) {
        try {
            llmExtractionsDao.save(cache(), sha, Config.CLAUDE_MODEL, json);
        } catch (Exception e) {
            // not cached: the next build asks again
        }
    }

    private Connection cache() throws Exception {
        if (cache == null) {
            cache = Db.open(Config.OCR_CACHE_FILE, true);
            llmExtractionsDao.createTable(cache);
        }
        return cache;
    }
}
