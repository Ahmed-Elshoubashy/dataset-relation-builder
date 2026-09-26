package com.dubsof.graph.dao.row;

/** A file that mentions an entity, and how the mention was matched (the Evidence list). */
public class SourceRow {
    public long fileId;
    public String path;
    public String kind;
    public String status;
    public String textSource;
    public String role;
    public String surface;
    public String method;
    public Double confidence;
}
