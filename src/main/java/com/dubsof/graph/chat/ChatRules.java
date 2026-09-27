package com.dubsof.graph.chat;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.row.EntityRow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat without an API key: a few question shapes, answered with the same tools Claude uses.
 * <ul>
 *   <li>"How many customers does the dataset have", "How many quotes were sent to Acme"</li>
 *   <li>"List all the quotes sent to Acme Corporation", "Who works for Acme"</li>
 *   <li>"Get me any aliases or abbreviations for Acme"</li>
 *   <li>"How is Acme connected to Falcon Aerospace"</li>
 *   <li>"Who is Thomas Bianchi", or just a name</li>
 * </ul>
 * Anything else gets a short list of what it understands.
 */
public class ChatRules {

    /** A plural word in a question -> what it asks for: an entity type, a document type, or the customers. */
    private static final Map<String, String[]> NOUNS = new LinkedHashMap<>();

    static {
        // {entity type, document type}; "customer" is a type of its own here
        NOUNS.put("customers?|clients?", new String[] {"customer", null});
        NOUNS.put("compan(?:y|ies)|organi[sz]ations?|suppliers?", new String[] {"company", null});
        NOUNS.put("people|persons?|contacts?|employees?|staff", new String[] {"person", null});
        NOUNS.put("projects?|jobs?", new String[] {"project", null});
        NOUNS.put("products?|parts?|machines?", new String[] {"product", null});
        NOUNS.put("quotes?|quotations?", new String[] {"document", "quote"});
        NOUNS.put("invoices?", new String[] {"document", "invoice"});
        NOUNS.put("purchase orders?|pos", new String[] {"document", "purchase_order"});
        NOUNS.put("delivery notes?", new String[] {"document", "delivery_note"});
        NOUNS.put("e-?mails?|mails?", new String[] {"document", "email"});
        NOUNS.put("letters?", new String[] {"document", "letter"});
        NOUNS.put("drawings?", new String[] {"document", "drawing"});
        NOUNS.put("contracts?", new String[] {"document", "contract"});
        NOUNS.put("reports?", new String[] {"document", "report"});
        NOUNS.put("documents?|files?", new String[] {"document", null});
    }

    private static final String NOUN = "(" + String.join("|", NOUNS.keySet()) + ")";
    /** Words that link the asked-for things to an entity: "quotes sent to X", "people at X", "projects of X". */
    private static final String LINK = "(?:(?:that\\s+were\\s+|which\\s+were\\s+|were\\s+)?(?:sent|issued|addressed|written|billed)\\s+to"
            + "|(?:working\\s+)?(?:for|at|of|from|by|with|to)|linked\\s+to|related\\s+to|about)";

    private static final Pattern ALIASES = Pattern.compile(
            "(?:aliases|alias|abbreviations?|other names?|spellings?|also known as|aka|variants?|names)\\b.*?\\b(?:for|of)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CALLED = Pattern.compile("what (?:else )?is (.+?) (?:also )?called", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTION = Pattern.compile(
            "(?:how (?:is|are)|connection between|path between|link between|relationship between|how do)\\s+(.+?)\\s+"
                    + "(?:connected to|related to|linked to|connected with|and)\\s+(.+?)(?:\\s+(?:connected|related|linked))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOW_MANY = Pattern.compile("how many\\s+(?:\\w+\\s+)?" + NOUN + "\\b(?:.*?\\b" + LINK + "\\s+(.+))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST = Pattern.compile(
            "(?:list|show|get|give|find|display|which|what)\\b(?:\\s+(?:me|us|are|were|is))*(?:\\s+(?:all|any|every))?(?:\\s+(?:of\\s+)?the)?"
                    + "(?:\\s+\\w+)?\\s+" + NOUN + "\\b.*?\\b" + LINK + "\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WHO_WORKS = Pattern.compile("who (?:works|worked|is working) (?:for|at)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHO_IS = Pattern.compile("(?:who|what) (?:is|are)\\s+(.+)|tell me about\\s+(.+)|show\\s+(.+)",
            Pattern.CASE_INSENSITIVE);
    /** "the customer Acme", "customer X": words before a name that are not part of it. */
    private static final Pattern LEADING_WORDS = Pattern.compile(
            "^(?:the\\s+)?(?:customer|company|client|supplier|person|project|product|document|entity)\\s+", Pattern.CASE_INSENSITIVE);
    /** Things a question may count "in", which are not entities. */
    private static final List<String> THE_DATASET = Arrays.asList("the dataset", "the data", "dataset", "the graph", "the files", "we", "us",
            "there", "the company", "our company");
    private static final int SHOWN = 20;

    private final ChatTools tools;
    private final ChatAnswer answer = new ChatAnswer();

    public ChatRules(ChatTools tools) {
        this.tools = tools;
        answer.engine = "rules";
    }

    public ChatAnswer answer(String question) throws Exception {
        String q = question.trim().replaceAll("[?.!]+$", "").trim();
        Matcher m;
        if ((m = ALIASES.matcher(q)).find() || (m = CALLED.matcher(q)).find()) {
            answer.answer = aliases(name(m.group(1)));
        } else if ((m = CONNECTION.matcher(q)).find()) {
            answer.answer = connection(name(m.group(1)), name(m.group(2)));
        } else if ((m = HOW_MANY.matcher(q)).find()) {
            answer.answer = howMany(NOUNS.get(nounKey(m.group(1))), m.group(2) == null ? null : name(m.group(2)));
        } else if ((m = WHO_WORKS.matcher(q)).find()) {
            answer.answer = list(new String[] {"person", null}, name(m.group(1)));
        } else if ((m = LIST.matcher(q)).find()) {
            answer.answer = list(NOUNS.get(nounKey(m.group(1))), name(m.group(2)));
        } else if ((m = WHO_IS.matcher(q)).find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            answer.answer = describe(name(name));
        } else {
            answer.answer = describe(q);
        }
        answer.entities.putAll(tools.seen());
        answer.focus = answer.focus != null ? answer.focus : tools.focus();
        return answer;
    }

    // ------------------------------------------------------------------ answers

    private String howMany(String[] noun, String target) throws Exception {
        if (target != null && !THE_DATASET.contains(target.toLowerCase())) {
            EntityRow entity = resolve(target, null);
            if (entity == null) {
                return notFound(target);
            }
            Map<String, Object> related = related(entity, noun, 1);
            return "**" + related.get("total") + "** " + plural(noun) + " linked to " + link(entity) + ".";
        }
        if ("customer".equals(noun[0])) {
            Map<String, Object> customers = call("find_entities", "customers_only", true, "limit", SHOWN);
            return "The dataset has **" + customers.get("total") + "** customers" + bullets(customers, false);
        }
        Map<String, Object> found = call("find_entities", "type", noun[0], "doc_type", noun[1], "limit", 1);
        return "The dataset has **" + found.get("total") + "** " + plural(noun) + ".";
    }

    private String list(String[] noun, String target) throws Exception {
        EntityRow entity = resolve(target, null);
        if (entity == null) {
            return notFound(target);
        }
        Map<String, Object> related = related(entity, noun, Config.CHAT_MAX_LIST);
        return "**" + related.get("total") + "** " + plural(noun) + " linked to " + link(entity) + bullets(related, true);
    }

    private String aliases(String target) throws Exception {
        EntityRow entity = resolve(target, null);
        if (entity == null) {
            return notFound(target);
        }
        Map<String, Object> result = call("get_aliases", "entity_id", entity.id);
        List<?> spellings = (List<?>) result.get("spellings");
        StringBuilder out = new StringBuilder(link(entity) + " is written **" + spellings.size() + "** "
                + (spellings.size() == 1 ? "way" : "ways") + " in the files:");
        for (Object s : spellings) {
            Map<?, ?> spelling = (Map<?, ?>) s;
            out.append("\n- “").append(spelling.get("alias")).append("” ×").append(spelling.get("count"))
                    .append(" (").append(String.valueOf(spelling.get("method")).replace('_', ' ')).append(")");
        }
        return out.toString();
    }

    private String connection(String from, String to) throws Exception {
        EntityRow a = resolve(from, null);
        EntityRow b = resolve(to, null);
        if (a == null || b == null) {
            return notFound(a == null ? from : to);
        }
        answer.focus = a.id;
        Map<String, Object> result = call("find_connection", "from_id", a.id, "to_id", b.id);
        List<?> paths = (List<?>) result.get("paths");
        if (paths.isEmpty()) {
            return link(a) + " and " + link(b) + " are not connected within 4 links" + notThrough(result) + ".";
        }
        StringBuilder out = new StringBuilder(link(a) + " and " + link(b) + " are **" + result.get("hops") + "** links apart"
                + notThrough(result) + ":");
        for (Object p : paths) {
            // "[[2|Acme]] PURCHASED_OR_QUOTED [[4922|SM-750]] (relation 11918)" -> "[[2|Acme]] purchased or quoted [[4922|SM-750]]"
            List<String> steps = new java.util.ArrayList<>();
            for (String step : castList(p)) {
                Matcher s = Pattern.compile("^(\\[\\[[^]]+]]) (\\w+) (\\[\\[[^]]+]]).*$").matcher(step);
                steps.add(s.matches() ? s.group(1) + " " + s.group(2).toLowerCase().replace('_', ' ') + " " + s.group(3) : step);
            }
            out.append("\n- ").append(String.join(" · ", steps));
        }
        return out.toString();
    }

    private String describe(String target) throws Exception {
        EntityRow entity = target.isEmpty() ? null : resolve(target, null);
        if (entity == null) {
            return "I can answer questions like:\n- How many customers does the dataset have?\n- List all the quotes sent to Acme Corporation\n"
                    + "- Get me any aliases or abbreviations for Acme\n- Who works for Acme?\n- How is Acme connected to Falcon Aerospace?\n"
                    + "- Who is Thomas Bianchi?\n\nAdd an Anthropic API key to ask in your own words.";
        }
        Map<String, Object> e = call("get_entity", "entity_id", entity.id);
        StringBuilder out = new StringBuilder(link(entity) + " is a " + entity.etype.value()
                + (e.get("subtitle") != null ? " (" + e.get("subtitle") + ")" : "") + " with **" + e.get("degree") + "** links:");
        for (Map.Entry<?, ?> count : ((Map<?, ?>) e.get("relation_counts")).entrySet()) {
            // "ISSUED_TO in document" -> "documents · issued to (in)"
            String[] parts = String.valueOf(count.getKey()).split(" ");
            out.append("\n- ").append(plural(new String[] {parts[2], null})).append(" · ")
                    .append(parts[0].toLowerCase().replace('_', ' ')).append(": ").append(count.getValue());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ helpers

    /** The entities of the asked-for kind linked to {@code entity}; also centres the graph on it. */
    private Map<String, Object> related(EntityRow entity, String[] noun, int limit) throws Exception {
        String type = "customer".equals(noun[0]) ? "company" : noun[0];
        return call("list_related", "entity_id", entity.id, "type", type, "doc_type", noun[1], "limit", limit);
    }

    /** The entity a name means (see ChatTools.resolve); the first one resolved is the one the graph centres on. */
    private EntityRow resolve(String name, String type) throws Exception {
        answer.usedTool("resolve_name", map("name", name));
        EntityRow entity = tools.resolve(name, type);
        if (entity != null && answer.focus == null) {
            answer.focus = entity.id;
        }
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String tool, Object... input) {
        Map<String, Object> in = map(input);
        answer.usedTool(tool, in);
        return (Map<String, Object>) tools.run(tool, in);
    }

    private static String bullets(Map<String, Object> result, boolean withRelation) {
        StringBuilder out = new StringBuilder(":");
        List<?> items = (List<?>) result.get("items");
        for (Object i : items) {
            Map<?, ?> item = (Map<?, ?>) i;
            out.append("\n- [[").append(item.get("id")).append("|").append(item.get("name")).append("]]");
            if (withRelation && item.get("rel") != null) {
                out.append(" (").append(String.valueOf(item.get("rel")).toLowerCase().replace('_', ' ')).append(")");
            }
        }
        long total = ((Number) result.get("total")).longValue();
        if (total > items.size()) {
            out.append("\n- … and ").append(total - items.size()).append(" more");
        }
        return items.isEmpty() ? "." : out.toString();
    }

    private static String link(EntityRow e) {
        return "[[" + e.id + "|" + e.name + "]]";
    }

    private static String notFound(String name) {
        return "I couldn't find anything called “" + name + "”.";
    }

    private static String notThrough(Map<String, Object> result) {
        List<?> avoided = (List<?>) result.get("not_through");
        return avoided == null || avoided.isEmpty() ? "" : " (not counting links through " + avoided.get(0) + ")";
    }

    private static String plural(String[] noun) {
        if ("customer".equals(noun[0])) {
            return "customers";
        }
        if (noun[1] != null) {
            return noun[1].replace('_', ' ') + "s";
        }
        return noun[0].equals("person") ? "people" : noun[0].equals("company") ? "companies" : noun[0] + "s";
    }

    /** "quotations" -> the NOUNS key whose pattern matches it. */
    private static String nounKey(String word) {
        for (String key : NOUNS.keySet()) {
            if (word.toLowerCase().matches(key)) {
                return key;
            }
        }
        return "documents?|files?";
    }

    /** A name from a question, without trailing words like "have" and leading ones like "customer". */
    private static String name(String text) {
        String n = text.trim().replaceAll("[?.!,]+$", "")
                .replaceAll("(?i)\\s+(?:have|has|had|got|in total|altogether|connected|related|linked)$", "").trim();
        return LEADING_WORDS.matcher(n).replaceFirst("").replaceAll("^[\"“'”]+|[\"“'”]+$", "").trim();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                m.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object list) {
        return (List<String>) list;
    }
}
