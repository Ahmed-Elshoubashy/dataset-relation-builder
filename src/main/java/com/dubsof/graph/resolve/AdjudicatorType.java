package com.dubsof.graph.resolve;

/**
 * Who decides borderline company matches (ERKG_ADJUDICATOR).
 * Each constant keeps the text used in the environment variable.
 */
public enum AdjudicatorType {
    /** Keep borderline pairs apart. */
    RULES("rules"),
    /** Ask Claude (needs ANTHROPIC_API_KEY). */
    CLAUDE("claude");

    private final String value;

    AdjudicatorType(String value) {
        this.value = value;
    }

    /** The text used in the environment variable. */
    public String value() {
        return value;
    }

    public static AdjudicatorType fromValue(String value) {
        for (AdjudicatorType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown adjudicator: " + value);
    }
}
