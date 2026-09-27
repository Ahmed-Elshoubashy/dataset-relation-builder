package com.dubsof.graph.chat;

import com.dubsof.graph.Config;
import com.dubsof.graph.api.GraphApi;
import com.dubsof.graph.dao.GraphQueries;
import com.dubsof.graph.dao.row.EntityRow;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-only tools the chat answers with, for Claude and for the offline rules alike. Each tool is one
 * question the graph can answer exactly ("the documents issued to entity 2 of type quote"); none writes
 * anything or runs free-form SQL. Every entity a tool returns is remembered, so the answer can show it as a
 * link, and {@code show_in_graph} records which entity the explorer should centre on.
 */
public class ChatTools {

    /** One tool as Claude sees it: its name, what it does, and its input as a JSON schema. */
    public static class Definition {
        public final String name;
        public final String description;
        public final Map<String, Object> properties;
        public final List<String> required;

        Definition(String name, String description, Map<String, Object> properties, String... required) {
            this.name = name;
            this.description = description;
            this.properties = properties;
            this.required = Arrays.asList(required);
        }
    }

    private static final List<String> TYPES = Arrays.asList("company", "person", "project", "document", "product");

    private final GraphApi api;
    private final GraphQueries graphQueries = new GraphQueries();
    private final Connection conn;
    /** Every entity a tool returned, by id: the answer's links. */
    private final Map<Long, Map<String, Object>> seen = new LinkedHashMap<>();
    /** The entity to centre the graph on (show_in_graph), or null. */
    private Long focus;

    public ChatTools(Connection conn) {
        this.conn = conn;
        this.api = new GraphApi(conn);
    }

    /** The tools, as offered to Claude. */
    public static List<Definition> definitions() {
        List<Definition> tools = new ArrayList<>();
        tools.add(new Definition("overview",
                "Counts for the whole graph: entities by type, customers, relations by type, the owner organisation, "
                        + "and how the graph was built. Use it for 'how many ...' questions about the whole dataset.",
                new LinkedHashMap<String, Object>()));
        tools.add(new Definition("find_entities",
                "Finds entities by (part of) a name, spelling or number, most connected first, with the total count. "
                        + "customers_only lists the owner's customers. Use it to turn a name from the question into an id.",
                props("query", string("Text to look for in names, spellings and numbers; omit to list all"),
                        "type", enumOf("Entity type", TYPES),
                        "doc_type", string("Documents only: invoice, quote, purchase_order, delivery_note, email, letter, drawing, contract, report, ..."),
                        "customers_only", bool("Only the owner's customers (companies)"),
                        "limit", integer("At most this many (default 20, max " + Config.CHAT_MAX_LIST + ")"))));
        tools.add(new Definition("get_entity",
                "One entity: its attributes, its spellings, and how many relations of each kind it has.",
                props("entity_id", integer("Entity id")), "entity_id"));
        tools.add(new Definition("list_related",
                "The entities linked to one entity, filtered, with the total count. Relations read 'src REL dst': "
                        + "'document ISSUED_TO company', 'person WORKS_FOR company', 'company HAS_PROJECT project', "
                        + "'project HAS_DOCUMENT document', 'person SENT document', 'document ATTENTION_OF person', "
                        + "'document LISTS_PRODUCT product'. direction 'in' means the other entity is the source "
                        + "(e.g. the documents ISSUED_TO a company are 'in' relations of the company).",
                props("entity_id", integer("Entity id"),
                        "rel", string("Relation type, e.g. ISSUED_TO, WORKS_FOR, HAS_PROJECT, HAS_DOCUMENT, SENT, MENTIONS"),
                        "direction", enumOf("in: the other entity is the source; out: it is the target", Arrays.asList("in", "out")),
                        "type", enumOf("Type of the linked entities", TYPES),
                        "doc_type", string("Linked documents of this type only: quote, invoice, ..."),
                        "limit", integer("At most this many (default 20, max " + Config.CHAT_MAX_LIST + ")")),
                "entity_id"));
        tools.add(new Definition("get_aliases",
                "Every spelling of an entity found in the files (abbreviations, typos, e-mail domains), with how often "
                        + "each was seen and the matching rule that tied it to the entity.",
                props("entity_id", integer("Entity id")), "entity_id"));
        tools.add(new Definition("find_connection",
                "The shortest paths between two entities (up to " + Config.CONNECTION_MAX_HOPS + " relations), "
                        + "never through the owner.",
                props("from_id", integer("Entity id"), "to_id", integer("Entity id")), "from_id", "to_id"));
        tools.add(new Definition("explain_relation",
                "Why two entities are linked: the files that state a relation and how each end is written there, or "
                        + "for a derived relation the rule and the documents it comes from. Takes a relation_id from list_related.",
                props("relation_id", integer("Relation id")), "relation_id"));
        tools.add(new Definition("show_in_graph",
                "Centres the explorer's graph on an entity, so the user sees it and its links. Call it when the answer "
                        + "is about one main entity.",
                props("entity_id", integer("Entity id")), "entity_id"));
        return tools;
    }

    /** Runs a tool; an unknown tool or a bad input gives {"error": ...}, which the caller passes on. */
    public Object run(String name, Map<String, Object> input) {
        try {
            switch (name) {
                case "overview":
                    return overview();
                case "find_entities":
                    return findEntities(str(input, "query"), str(input, "type"), str(input, "doc_type"),
                            Boolean.TRUE.equals(input.get("customers_only")), limit(input));
                case "get_entity":
                    return getEntity(id(input, "entity_id"));
                case "list_related":
                    return listRelated(id(input, "entity_id"), str(input, "rel"), str(input, "direction"), str(input, "type"),
                            str(input, "doc_type"), limit(input));
                case "get_aliases":
                    return getAliases(id(input, "entity_id"));
                case "find_connection":
                    return findConnection(id(input, "from_id"), id(input, "to_id"));
                case "explain_relation":
                    return explainRelation(id(input, "relation_id"));
                case "show_in_graph":
                    return showInGraph(id(input, "entity_id"));
                default:
                    return error("unknown tool " + name);
            }
        } catch (Exception e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the tools

    Map<String, Object> overview() throws Exception {
        Map<String, Object> stats = api.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entities", stats.get("entities"));
        out.put("customers", graphQueries.findCustomers(conn).size());
        List<EntityRow> owners = graphQueries.findOwners(conn);
        out.put("owner", owners.isEmpty() ? null : entity(owners.get(0)));
        out.put("relations_total", stats.get("relations"));
        out.put("files", stats.get("files"));
        out.put("built_from", stats.get("meta"));
        return out;
    }

    Map<String, Object> findEntities(String query, String type, String docType, boolean customersOnly, int limit) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        long total;
        if (customersOnly) {
            List<EntityRow> customers = graphQueries.findCustomers(conn);
            total = customers.size();
            for (EntityRow customer : customers) {
                if (items.size() < limit && (query == null || customer.name.toLowerCase().contains(query.toLowerCase()))) {
                    items.add(entity(customer));
                }
            }
        } else {
            total = graphQueries.countEntities(conn, type, docType, query);
            for (EntityRow e : graphQueries.searchEntities(conn, type, docType, query, limit, 0)) {
                items.add(entity(e));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    Map<String, Object> getEntity(long id) throws Exception {
        Map<String, Object> e = api.entity(id);
        remember(e);
        Map<String, Object> out = brief(e);
        out.put("attrs", trimmed(e.get("attrs")));
        List<?> aliases = (List<?>) e.get("aliases");
        out.put("spellings", aliases.subList(0, Math.min(15, aliases.size())));
        // relation counts by (rel, direction, type), not the relations themselves: list_related gives those
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object r : (List<?>) e.get("relations")) {
            Map<?, ?> rel = (Map<?, ?>) r;
            String key = rel.get("rel") + " " + rel.get("dir") + " " + rel.get("type");
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        out.put("relation_counts", counts);
        return out;
    }

    Map<String, Object> listRelated(long id, String rel, String direction, String type, String docType, int limit) throws Exception {
        Map<String, Object> e = api.entity(id);
        remember(e);
        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        for (Object r : (List<?>) e.get("relations")) {
            Map<?, ?> related = (Map<?, ?>) r;
            if ((rel == null || rel.equalsIgnoreCase(String.valueOf(related.get("rel"))))
                    && (direction == null || direction.equals(related.get("dir")))
                    && (type == null || type.equals(related.get("type")))
                    && (docType == null || docType.equals(related.get("doc_type")))) {
                total++;
                if (items.size() < limit) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", related.get("id"));
                    item.put("type", related.get("type"));
                    item.put("name", related.get("name"));
                    item.put("doc_type", related.get("doc_type"));
                    item.put("rel", related.get("rel"));
                    item.put("direction", related.get("dir"));
                    item.put("weight", related.get("weight"));
                    item.put("derived", related.get("derived"));
                    items.add(item);
                    remember(item);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", brief(e));
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    Map<String, Object> getAliases(long id) throws Exception {
        Map<String, Object> e = api.entity(id);
        remember(e);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", brief(e));
        out.put("spellings", e.get("aliases"));
        return out;
    }

    Map<String, Object> findConnection(long from, long to) throws Exception {
        Map<String, Object> c = api.connection(from, to, true);
        Map<Long, String> names = new LinkedHashMap<>();
        for (Object n : (List<?>) c.get("nodes")) {
            Map<?, ?> node = (Map<?, ?>) n;
            remember(node);
            names.put(((Number) node.get("id")).longValue(), String.valueOf(node.get("name")));
        }
        // each path as readable steps: "Acme Corporation PURCHASED_OR_QUOTED Servo Motor SM-750"
        List<List<String>> paths = new ArrayList<>();
        for (Object p : (List<?>) c.get("paths")) {
            List<String> steps = new ArrayList<>();
            for (Object s : (List<?>) ((Map<?, ?>) p).get("steps")) {
                Map<?, ?> step = (Map<?, ?>) s;
                long src = ((Number) step.get("src")).longValue();
                long dst = ((Number) step.get("dst")).longValue();
                steps.add("[[" + src + "|" + names.get(src) + "]] " + step.get("rel") + " [[" + dst + "|" + names.get(dst) + "]]"
                        + " (relation " + step.get("id") + ")");
            }
            paths.add(steps);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hops", c.get("hops"));
        out.put("paths", paths);
        out.put("not_through", c.get("avoided"));
        return out;
    }

    Map<String, Object> explainRelation(long relationId) throws Exception {
        Map<String, Object> r = api.relation(relationId);
        remember((Map<?, ?>) r.get("src_entity"));
        remember((Map<?, ?>) r.get("dst_entity"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relation", r.get("src_entity") == null ? null : ((Map<?, ?>) r.get("src_entity")).get("name") + " " + r.get("rel")
                + " " + ((Map<?, ?>) r.get("dst_entity")).get("name"));
        out.put("stated_in_files", r.get("weight"));
        out.put("derived", r.get("derived"));
        List<?> evidence = (List<?>) r.get("evidence");
        out.put("files", evidence.subList(0, Math.min(10, evidence.size())));
        List<?> via = (List<?>) r.get("via");
        for (Object v : via) {
            remember((Map<?, ?>) v);
        }
        out.put("rule", r.get("rule"));
        out.put("inferred_from", via.subList(0, Math.min(10, via.size())));
        return out;
    }

    Map<String, Object> showInGraph(long id) throws Exception {
        Map<String, Object> e = api.entity(id);
        remember(e);
        focus = id;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shown", brief(e));
        return out;
    }

    // ------------------------------------------------------------------ for the offline rules

    /**
     * The entity a name in a question means: an exact name or spelling first, else the most connected entity
     * whose name or spelling contains it; of the given type when there is one. Null when nothing matches.
     */
    public EntityRow resolve(String name, String type) throws Exception {
        EntityRow exact = graphQueries.findExactMatch(conn, type, name);
        if (exact != null) {
            return exact;
        }
        List<EntityRow> found = graphQueries.searchEntities(conn, type, null, name, 1, 0);
        return found.isEmpty() ? null : found.get(0);
    }

    // ------------------------------------------------------------------ results

    /** Every entity the tools returned, by id, for the answer's links. */
    public Map<Long, Map<String, Object>> seen() {
        return seen;
    }

    public Long focus() {
        return focus;
    }

    /** An entity as the tools return it: id, type, name, and a short context. */
    private Map<String, Object> entity(EntityRow row) throws Exception {
        Map<String, Object> e = api.summary(row);
        remember(e);
        return brief(e);
    }

    private static Map<String, Object> brief(Map<?, ?> e) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : new String[] {"id", "type", "name", "key", "subtitle", "doc_type", "degree"}) {
            if (e.get(field) != null) {
                out.put(field, e.get(field));
            }
        }
        return out;
    }

    private void remember(Map<?, ?> e) {
        if (e != null && e.get("id") != null) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("id", e.get("id"));
            link.put("type", e.get("type"));
            link.put("name", e.get("name"));
            seen.putIfAbsent(((Number) e.get("id")).longValue(), link);
        }
    }

    /** Attributes without their long lists (line items, file lists): the first few of each. */
    private static Object trimmed(Object attrs) {
        if (!(attrs instanceof Map)) {
            return attrs;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> a : ((Map<?, ?>) attrs).entrySet()) {
            Object value = a.getValue();
            if (value instanceof List && ((List<?>) value).size() > 10) {
                List<?> list = (List<?>) value;
                List<Object> first = new ArrayList<Object>(list.subList(0, 10));
                first.add("… " + (list.size() - 10) + " more");
                value = first;
            }
            out.put(String.valueOf(a.getKey()), value);
        }
        return out;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        return out;
    }

    // ------------------------------------------------------------------ input helpers

    private static String str(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null || String.valueOf(v).trim().isEmpty() ? null : String.valueOf(v).trim();
    }

    private static long id(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof Number) && !(v instanceof String && ((String) v).matches("\\d+"))) {
            throw new IllegalArgumentException(key + " must be an entity id");
        }
        return v instanceof Number ? ((Number) v).longValue() : Long.parseLong((String) v);
    }

    private static int limit(Map<String, Object> input) {
        Object v = input.get("limit");
        int limit = v instanceof Number ? ((Number) v).intValue() : 20;
        return Math.max(1, Math.min(limit, Config.CHAT_MAX_LIST));
    }

    // ------------------------------------------------------------------ schema helpers

    private static Map<String, Object> props(Object... nameSchemaPairs) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) {
            props.put((String) nameSchemaPairs[i], nameSchemaPairs[i + 1]);
        }
        return props;
    }

    private static Map<String, Object> string(String description) {
        return schema("string", description);
    }

    private static Map<String, Object> integer(String description) {
        return schema("integer", description);
    }

    private static Map<String, Object> bool(String description) {
        return schema("boolean", description);
    }

    private static Map<String, Object> enumOf(String description, List<String> values) {
        Map<String, Object> s = schema("string", description);
        s.put("enum", values);
        return s;
    }

    private static Map<String, Object> schema(String type, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("description", description);
        return s;
    }
}
