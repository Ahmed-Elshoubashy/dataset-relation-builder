package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.RelationType;

import java.util.ArrayList;
import java.util.List;

/**
 * What the general extractor found in a text: named entities and the relations between them.
 * Claude's answer and the offline rules both produce this, so the rest of LlmParser treats them the same.
 */
final class TextFindings {

    /** The name relations use for the document being read. */
    static final String THIS_DOCUMENT = "this document";

    /** One named thing. {@code email}, {@code organisation} and {@code jobTitle} are for people, often null. */
    static final class Found {
        final EntityType type;
        final String name;
        final String email;
        final String organisation;
        final String jobTitle;

        Found(EntityType type, String name, String email, String organisation, String jobTitle) {
            this.type = type;
            this.name = name;
            this.email = email;
            this.organisation = organisation;
            this.jobTitle = jobTitle;
        }
    }

    /** "src REL dst", naming entities of the list (or THIS_DOCUMENT). */
    static final class FoundRelation {
        final String src;
        final RelationType rel;
        final String dst;

        FoundRelation(String src, RelationType rel, String dst) {
            this.src = src;
            this.rel = rel;
            this.dst = dst;
        }
    }

    final List<Found> entities = new ArrayList<>();
    final List<FoundRelation> relations = new ArrayList<>();

    void add(EntityType type, String name, String email, String organisation, String jobTitle) {
        entities.add(new Found(type, name, email, organisation, jobTitle));
    }
}
