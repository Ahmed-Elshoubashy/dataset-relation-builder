package com.dubsof.graph.dao.row;

/**
 * A file that states a relation, with one mention in it of either end of the relation (how it is written
 * there, and in which role). A file with several such mentions gives several rows; one with none, a row
 * whose mention fields are null.
 */
public class EvidenceRow {
    public long fileId;
    public String path;
    public String kind;
    public String status;
    public Long entityId;
    public String surface;
    public String role;
    public String method;
    public Double confidence;
}
