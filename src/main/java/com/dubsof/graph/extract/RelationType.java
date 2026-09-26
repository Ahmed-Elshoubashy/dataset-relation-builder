package com.dubsof.graph.extract;

/**
 * The link types of facts (between mentions of one file) and relations (between entities).
 * The constant name is the text stored in the {@code rel} column, so the schema and the API stay unchanged.
 * Each comment reads "src -> dst".
 */
public enum RelationType {

    // ---- stated in a file (facts, then relations)

    /** document -> company it is billed / issued to */
    ISSUED_TO,
    /** document -> company a letter or invite is addressed to */
    ADDRESSED_TO,
    /** document -> person named on "Attn:" */
    ATTENTION_OF,
    /** person -> company */
    WORKS_FOR,
    /** company -> project */
    HAS_PROJECT,
    /** project -> document */
    HAS_DOCUMENT,
    /** document -> company whose folder holds it (only when there is no project folder) */
    FILED_UNDER,
    /** document -> product on one of its lines */
    LISTS_PRODUCT,
    /** document -> product it is about (spec, datasheet, manual), or contact card -> person */
    DESCRIBES,
    /** document -> another document it cites by number */
    REFERENCES,
    /** person -> document they wrote or signed */
    AUTHORED,
    /** person -> e-mail they sent */
    SENT,
    /** person -> e-mail they received (To or Cc) */
    RECEIVED,
    /** person -> meeting notes they attended */
    ATTENDED,
    /** company -> contract it is a party to */
    PARTY_TO,
    /** company or person -> certificate they hold */
    HOLDS,
    /** certificate -> company that issued it */
    ISSUED_BY,
    /** attachment or archive member -> the e-mail / zip it came from */
    ATTACHED_TO,

    // ---- added by the Relator

    /** document -> company or person its free text names (gazetteer) */
    MENTIONS,
    /** derived: person -> project, through documents they authored / sent / received / were addressed on */
    INVOLVED_IN,
    /** derived: project -> product, through the project's line items, specs and datasheets */
    USES_PRODUCT,
    /** derived: company -> product it was invoiced or quoted */
    PURCHASED_OR_QUOTED;

    /** The text stored in the database. */
    public String value() {
        return name();
    }

    public static RelationType fromValue(String value) {
        return valueOf(value);
    }
}
