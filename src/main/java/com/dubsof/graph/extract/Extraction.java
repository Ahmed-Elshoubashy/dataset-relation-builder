package com.dubsof.graph.extract;

import com.dubsof.graph.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything found in one file: mentions (raw references), facts (links between those
 * mentions) and data-quality issues. Mentions are referred to by their index in the list.
 */
public class Extraction {

    /** A reference to an entity exactly as it appears in the file. */
    public static class Mention {
        public final EntityType etype;
        public final String surface;
        public final MentionRole role;
        public final double confidence;
        public final Map<String, Object> attrs = new LinkedHashMap<>();

        Mention(EntityType etype, String surface, MentionRole role, double confidence) {
            this.etype = etype;
            this.surface = surface;
            this.role = role;
            this.confidence = confidence;
        }
    }

    /** "mention src REL mention dst", e.g. document ISSUED_TO company. */
    public static class Fact {
        public final int src;
        public final RelationType rel;
        public final int dst;

        Fact(int src, RelationType rel, int dst) {
            this.src = src;
            this.rel = rel;
            this.dst = dst;
        }
    }

    public final long fileId;
    public final List<Mention> mentions = new ArrayList<>();
    public final List<Fact> facts = new ArrayList<>();
    public final List<String[]> issues = new ArrayList<>();   // {kind, severity, detail}
    /** Index of this file's own document mention. */
    public Integer doc;

    public Extraction(long fileId) {
        this.fileId = fileId;
    }

    /**
     * Adds a mention with a confidence below 1 (or updates an identical one) and returns its index,
     * or null for empty text.
     *
     * @param attrs alternating key, value pairs; null values are skipped
     */
    // TODO revisit this
    public Integer addMentionWithConfidence(EntityType etype, String surface, MentionRole role, double confidence, Object... attrs) {
        surface = stripChars(Text.collapseSpaces(surface), " ,;:");
        if (surface.isEmpty()) {
            return null;
        }
        Mention found = null;
        int index = -1;
        for (int i = 0; i < mentions.size(); i++) {
            Mention x = mentions.get(i);
            if (x.etype == etype && x.surface.equals(surface) && x.role == role) {
                found = x;
                index = i;
                break;
            }
        }
        if (found == null) {
            found = new Mention(etype, surface, role, confidence);
            mentions.add(found);
            index = mentions.size() - 1;
        }
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            if (attrs[i + 1] != null) {
                found.attrs.put((String) attrs[i], attrs[i + 1]);
            }
        }
        return index;
    }

    /** Adds a mention with full confidence. */
    public Integer addMention(EntityType etype, String surface, MentionRole role, Object... attrs) {
        return addMentionWithConfidence(etype, surface, role, 1.0, attrs);
    }

    public void fact(Integer src, RelationType rel, Integer dst) {
        if (src != null && dst != null && !src.equals(dst)) {
            facts.add(new Fact(src, rel, dst));
        }
    }

    public void issue(String kind, String severity, String detail) {
        issues.add(new String[] {kind, severity, detail});
    }

    public Mention docMention() {
        return mentions.get(doc);
    }

    private static String stripChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }
}
