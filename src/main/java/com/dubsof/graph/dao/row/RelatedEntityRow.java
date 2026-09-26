package com.dubsof.graph.dao.row;

/** An entity linked to another one, seen from that other entity (for its details panel). */
public class RelatedEntityRow {
    public String rel;
    public long weight;
    public long derived;
    /** "out" when the other entity is the source of the relation, "in" when it is the target. */
    public String dir;
    public long id;
    public String type;
    public String name;
    public String docType;
}
