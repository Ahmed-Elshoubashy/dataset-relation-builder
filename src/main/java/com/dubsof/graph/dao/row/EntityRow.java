package com.dubsof.graph.dao.row;

import java.util.Map;

/** One row of the {@code entities} table: a resolved real-world thing. */
public class EntityRow {
    public long id;
    /** company, person, project, document or product. */
    public String etype;
    /** Canonical name. */
    public String name;
    /** Identity key, unique per type (e.g. "acme", "JOB-2024-0006", "QUO-5238"). */
    public String key;
    public Map<String, Object> attrs;
    /** Number of relations; only filled by queries that compute it, otherwise null. */
    public Long degree;
}
