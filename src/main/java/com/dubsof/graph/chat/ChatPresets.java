package com.dubsof.graph.chat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chat without an API key: four fixed questions, each answered from the graph by the same read-only
 * {@link ChatTools} Claude uses. There is no text matching: the explorer offers these questions as buttons,
 * and free text needs Claude.
 */
public class ChatPresets {

    /** The fixed questions, by id, with the text the explorer shows. */
    public static final Map<String, String> QUESTIONS = new LinkedHashMap<>();

    static {
        QUESTIONS.put("entities", "What are the entities?");
        QUESTIONS.put("customers", "How many customers are there?");
        QUESTIONS.put("people", "How many people are there?");
        QUESTIONS.put("owner", "Who is the owner?");
    }

    private final ChatTools tools;
    private final ChatAnswer answer = new ChatAnswer();

    public ChatPresets(ChatTools tools) {
        this.tools = tools;
        answer.engine = "preset";
    }

    /** The answer to one of {@link #QUESTIONS}; an unknown id is an IllegalArgumentException. */
    public ChatAnswer answer(String id) throws Exception {
        if (!QUESTIONS.containsKey(id)) {
            throw new IllegalArgumentException("Unknown question: " + id);
        }
        if (id.equals("entities")) {
            answer.answer = entities();
        } else if (id.equals("customers")) {
            answer.answer = customers();
        } else if (id.equals("people")) {
            answer.answer = people();
        } else {
            answer.answer = owner();
        }
        answer.entities.putAll(tools.seen());
        return answer;
    }

    /** The kinds of entity in the graph and how many of each, the customers, and the owner. */
    private String entities() throws Exception {
        Map<String, Object> overview = call("overview");
        Map<?, ?> counts = (Map<?, ?>) overview.get("entities");
        long total = 0;
        for (Object n : counts.values()) {
            total += ((Number) n).longValue();
        }
        StringBuilder out = new StringBuilder("The graph has **" + total + "** entities of five kinds:");
        // {type, one, many, what they are}
        List<String[]> kinds = Arrays.asList(
                new String[] {"company", "company", "companies", "the owner, its customers and suppliers"},
                new String[] {"person", "person", "people", "senders, signatories and contacts named in the files"},
                new String[] {"project", "project", "projects", "jobs, from project folders or titles"},
                new String[] {"document", "document", "documents", "invoices, quotes, e-mails, letters, drawings, ..."},
                new String[] {"product", "product", "products", "equipment and parts, by product code"});
        for (String[] kind : kinds) {
            long n = counts.get(kind[0]) == null ? 0 : ((Number) counts.get(kind[0])).longValue();
            out.append("\n- **").append(n).append("** ").append(n == 1 ? kind[1] : kind[2]);
            if (kind[0].equals("company")) {
                out.append(" (**").append(overview.get("customers")).append("** customers)");
            }
            out.append(": ").append(kind[3]);
        }
        Map<?, ?> owner = (Map<?, ?>) overview.get("owner");
        if (owner != null) {
            out.append("\n\nThe owner is [[").append(owner.get("id")).append("|").append(owner.get("name")).append("]].");
        }
        return out.toString();
    }

    /** How many customers, and which (most connected first). */
    private String customers() throws Exception {
        Map<String, Object> customers = call("find_entities", "customers_only", true, "limit", 50);
        List<?> items = (List<?>) customers.get("items");
        StringBuilder out = new StringBuilder("The dataset has **" + customers.get("total") + "** customers");
        if (items.isEmpty()) {
            return out.append(".").toString();
        }
        out.append(":");
        for (Object i : items) {
            Map<?, ?> item = (Map<?, ?>) i;
            out.append("\n- [[").append(item.get("id")).append("|").append(item.get("name")).append("]]");
        }
        long more = ((Number) customers.get("total")).longValue() - items.size();
        if (more > 0) {
            out.append("\n- … and ").append(more).append(" more");
        }
        return out.toString();
    }

    private String people() throws Exception {
        Map<String, Object> people = call("find_entities", "type", "person", "limit", 1);
        return "The dataset has **" + people.get("total") + "** people.";
    }

    /** The organisation whose files these are; the graph centres on it. */
    private String owner() throws Exception {
        Map<?, ?> owner = (Map<?, ?>) call("overview").get("owner");
        if (owner == null) {
            return "No owner organisation was found in these files: no letterhead on many PDFs, and no company e-mail"
                    + " domain on most e-mails.";
        }
        long id = ((Number) owner.get("id")).longValue();
        Map<String, Object> e = call("get_entity", "entity_id", id);
        answer.focus = id;
        Object domain = ((Map<?, ?>) e.get("attrs")).get("domain");
        return "The owner is [[" + id + "|" + owner.get("name") + "]]" + (domain == null ? "" : " (" + domain + ")")
                + ": the organisation whose files these are. It has **" + e.get("degree") + "** links.";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String tool, Object... input) {
        Map<String, Object> in = new LinkedHashMap<>();
        for (int i = 0; i + 1 < input.length; i += 2) {
            in.put((String) input[i], input[i + 1]);
        }
        answer.usedTool(tool, in);
        return (Map<String, Object>) tools.run(tool, in);
    }
}
