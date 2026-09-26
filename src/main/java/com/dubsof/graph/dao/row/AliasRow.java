package com.dubsof.graph.dao.row;

import com.dubsof.graph.extract.EntityType;

/** One row of the {@code aliases} table: one spelling of an entity and how it was matched. */
public class AliasRow {
    public long entityId;
    public String alias;
    public String method;
    public double confidence;
    /** How many mentions use this spelling. */
    public long count;
    /** The entity's type; only filled by queries that join entities, otherwise null. */
    public EntityType entityType;
}
