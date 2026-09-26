package com.dubsof.graph.extract;

/**
 * Where in a file a mention was found (the {@code mentions.role} column).
 * The Resolver uses it to decide how much to trust a name, e.g. a customer folder name over a filename:
 * the number after a role that can name a company is its {@link #companyRank()}.
 * The stored text is the constant name in lower case, so the schema and the API stay unchanged.
 */
public enum MentionRole {

    // ---- found by the Extractor itself, for every file

    /** The file's own document. */
    SELF,
    /** Company or project from the folder path (Customers/&lt;company&gt;/JOB-.../). */
    FOLDER(0),
    /** Company or product guessed from the file name ("INV-8002_Acme Corporation", "GB-40_..."). */
    FILENAME(12),
    /** Document number or JOB code cited anywhere in the text. */
    REFERENCE,

    // ---- companies

    /** The owner, implied as the author's employer on documents it generates. */
    IMPLIED_OWNER(1),
    /** First line of an invoice / quote / PO / delivery note "BILL TO:" block. */
    BILL_TO(2),
    /** One side of "Between X and Y" in a contract. */
    CONTRACT_PARTY(3),
    /** "Customer:" on a drawing. */
    DRAWING_CUSTOMER(4),
    /** First line of a letter's address block. */
    LETTER_RECIPIENT(5),
    /** ORG in a contact card. */
    VCARD_ORG(6),
    /** Company or project in a calendar invite's "Site visit - project (company)" summary. */
    CALENDAR_SUMMARY(7),
    /** Company an ISO certificate is issued to. */
    CERTIFIED_COMPANY(8),
    /** Body that issued an ISO certificate. */
    CERTIFICATION_BODY(10),
    /** Provider of a training certificate. */
    TRAINING_PROVIDER(11),
    /** Company or project in a row of an OCR'd app screenshot. */
    SCREENSHOT_ROW(9),
    /** Company guessed from an e-mail address's domain. */
    EMAIL_DOMAIN(13),

    // ---- people

    /** "Attn:" on a business document. */
    ATTN,
    /** Name under a letter's sign-off. */
    SIGNATORY,
    /** "Drawn By:" on a drawing. */
    DRAWN_BY,
    /** "Technician:" on a calibration certificate. */
    TECHNICIAN,
    /** Author of an internal or service report. */
    REPORT_AUTHOR,
    /** Person a training certificate is issued to. */
    CERTIFICATE_HOLDER,
    /** Name in a meeting's "Attendees:" line. */
    ATTENDEE,
    /** E-mail From / To / Cc. */
    EMAIL_FROM,
    EMAIL_TO,
    EMAIL_CC,
    /** FN in a contact card. */
    VCARD,

    // ---- projects

    /** "Job:" / "Relating to:" field, or a project named in a letter. */
    DOC_JOB_FIELD,
    /** Project named in an e-mail subject or body. */
    EMAIL_SUBJECT,

    // ---- products

    /** Line of an invoice or price list with a product code. */
    LINE_ITEM,
    /** Product in a specification or datasheet title. */
    SPEC_TITLE,
    /** Product in a manual's title. */
    MANUAL_TITLE,

    // ---- added by the Relator

    /** Known company or person name found in free text by the gazetteer. */
    TEXT_MENTION;

    /** Rank of roles that never name a company: after all the others. */
    private static final int NOT_A_COMPANY_ROLE = 99;

    private final int companyRank;

    MentionRole() {
        this(NOT_A_COMPANY_ROLE);
    }

    MentionRole(int companyRank) {
        this.companyRank = companyRank;
    }

    /**
     * How much to trust a company name found in this role: 0 (a customer folder name) is the most
     * trustworthy, higher numbers less. The Resolver resolves company mentions in this order, so a good
     * spelling creates each company before weaker ones (a truncated filename, an e-mail domain) are matched to it.
     */
    public int companyRank() {
        return companyRank;
    }

    /** The text stored in the database. */
    public String value() {
        return name().toLowerCase();
    }

    public static MentionRole fromValue(String value) {
        return valueOf(value.toUpperCase());
    }
}
