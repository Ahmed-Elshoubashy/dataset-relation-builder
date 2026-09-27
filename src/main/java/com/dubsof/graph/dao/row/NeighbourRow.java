package com.dubsof.graph.dao.row;

/** One relation of an entity to a neighbour, seen from the entity: for counting what a graph view leaves out. */
public class NeighbourRow {
    public long id;
    public String type;
    public String rel;
    /** "out" when the entity is the source of the relation, "in" when it is the target. */
    public String dir;
}
