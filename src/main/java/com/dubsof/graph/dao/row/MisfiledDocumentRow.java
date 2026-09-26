package com.dubsof.graph.dao.row;

/** A document filed under one customer's project folder but addressed to another company. */
public class MisfiledDocumentRow {
    public long documentId;
    public String documentKey;
    public String folderCompany;
    public String addressedTo;
}
