package com.dubsof.graph.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chat answer. The text refers to entities as {@code [[id|name]]}, which the explorer shows as links;
 * {@code entities} gives each one's type and name, and {@code focus} the entity to centre the graph on.
 */
public class ChatAnswer {
    public String answer;
    /** "claude", or "preset" for a fixed question answered without Claude. */
    public String engine;
    /** Something the user should know, e.g. that Claude stopped after too many tool calls; or null. */
    public String notice;
    public Long focus;
    public Map<Long, Map<String, Object>> entities = new LinkedHashMap<>();
    /** The tools used, in order, with their input: how the answer was found. */
    public List<Map<String, Object>> tools = new ArrayList<>();

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer);
        out.put("engine", engine);
        out.put("notice", notice);
        out.put("focus", focus);
        out.put("entities", new ArrayList<>(entities.values()));
        out.put("tools", tools);
        return out;
    }

    void usedTool(String name, Map<String, Object> input) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("name", name);
        call.put("input", input);
        tools.add(call);
    }
}
