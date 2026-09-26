package com.dubsof.graph.dao.row;

/** A document number issued to more than one company. */
public class MultiCustomerDocumentRow {
    public long documentId;
    public String documentKey;
    /** Comma-separated company names. */
    public String companies;
}
