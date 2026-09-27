package com.dubsof.graph.extract;

/**
 * What a mention or entity is (the {@code etype} column of mentions and entities).
 * Each constant keeps the text stored in the database, so the schema and the API stay unchanged.
 */
public enum EntityType {
    /** A customer, supplier, certification body, ... or the owner. */
    COMPANY("company"),
    /** Someone named in a document: signatory, recipient, author, attendee, ... */
    PERSON("person"),
    /** A job or project, usually identified by a job id (its format comes from the dataset's profile). */
    PROJECT("project"),
    /** A file's own document (invoice, letter, e-mail, ...) or one it references by number. */
    DOCUMENT("document"),
    /** Equipment or part with a product code like HL-6200. */
    PRODUCT("product");

    private final String value;

    EntityType(String value) {
        this.value = value;
    }

    /** The text stored in the database. */
    public String value() {
        return value;
    }

    public static EntityType fromValue(String value) {
        for (EntityType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown entity type: " + value);
    }
}
