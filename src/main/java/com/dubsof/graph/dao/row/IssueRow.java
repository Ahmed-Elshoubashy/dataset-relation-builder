package com.dubsof.graph.dao.row;

/** One row of the {@code issues} table (a data-quality finding), optionally with the names it points at. */
public class IssueRow {
    public long id;
    public String kind;
    /** info, warn or error. */
    public String severity;
    public String detail;
    public Long fileId;
    public Long entityId;
    /** Joined columns; only filled by queries that join files / entities, otherwise null. */
    public String path;
    public String entityName;
    public String entityType;
}
