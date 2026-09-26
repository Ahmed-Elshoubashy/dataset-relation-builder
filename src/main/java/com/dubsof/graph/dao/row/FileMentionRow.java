package com.dubsof.graph.dao.row;

/** A mention in one file, with the entity it resolved to (null when unresolved). */
public class FileMentionRow {
    public String surface;
    public String role;
    public String method;
    public Double confidence;
    public Long entityId;
    public String entityName;
    public String entityType;
}
