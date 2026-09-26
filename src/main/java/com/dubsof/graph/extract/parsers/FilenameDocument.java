package com.dubsof.graph.extract.parsers;

/**
 * What a numbered file name says: "INV-8002_Acme Corporation" is document INV-8002, an invoice,
 * for "Acme Corporation". Every field is null when the name has no document number.
 */
public final class FilenameDocument {

    /** "INV-8002", or null. */
    public final String number;
    /** "invoice", "quote", "drawing", ... from the number's prefix, or null. */
    public final String docType;
    /** The company after the number ("Acme Corporation"), often cut off; null when there is none. */
    public final String company;

    public FilenameDocument(String number, String docType, String company) {
        this.number = number;
        this.docType = docType;
        this.company = company;
    }
}
