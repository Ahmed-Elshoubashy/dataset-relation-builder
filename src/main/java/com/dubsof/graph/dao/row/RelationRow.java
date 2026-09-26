package com.dubsof.graph.dao.row;

/** One row of the {@code relations} table: "entity src REL entity dst". */
public class RelationRow {
    public long id;
    public long src;
    public long dst;
    public String rel;
    /** Number of files stating it. */
    public long weight;
    /** 1 for shortcuts computed from other relations, 0 otherwise. */
    public long derived;
}
