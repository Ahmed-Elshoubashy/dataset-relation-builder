package com.dubsof.graph.dao.row;

/** A fact whose two mentions are both resolved: "entity src REL entity dst", stated in one file. */
public class EntityFactRow {
    public long fileId;
    public long srcEntityId;
    public String rel;
    public long dstEntityId;
}
