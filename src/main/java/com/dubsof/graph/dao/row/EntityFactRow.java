package com.dubsof.graph.dao.row;

import com.dubsof.graph.extract.RelationType;

/** A fact whose two mentions are both resolved: "entity src REL entity dst", stated in one file. */
public class EntityFactRow {
    public long fileId;
    public long srcEntityId;
    public RelationType rel;
    public long dstEntityId;
}
