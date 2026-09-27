package com.dubsof.graph.dao.row;

import com.dubsof.graph.extract.EntityType;

import java.util.Map;

/** One row of the {@code entities} table: a resolved real-world thing. */
public class EntityRow {
    public long id;
    /** company, person, project, document or product. */
    public EntityType etype;
    /** Canonical name. */
    public String name;
    /** Identity key, unique per type (e.g. "acme" for a company, a job id for a project, "QUO-5238" for a document). */
    public String key;
    public Map<String, Object> attrs;
    /** Number of relations; only filled by queries that compute it, otherwise null. */
    public Long degree;
}
