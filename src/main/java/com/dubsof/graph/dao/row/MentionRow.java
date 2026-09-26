package com.dubsof.graph.dao.row;

import com.dubsof.graph.extract.EntityType;

import java.util.Map;

/**
 * One row of the {@code mentions} table: a reference to an entity exactly as it appears in a file.
 * The resolver fills in entityId, method and confidence.
 */
public class MentionRow {
    public long id;
    public long fileId;
    /** company, person, project, document or product. */
    public EntityType etype;
    /** The text as written ("BFG Ltd"). */
    public String surface;
    /** Where it was seen (bill_to, folder, email_from, ...). */
    public String role;
    public Map<String, Object> attrs;
    public Long entityId;
    /** The matching rule that linked it (abbreviation, typo, email ...). */
    public String method;
    public Double confidence;

    /** An attribute holding an id (e.g. company_mention), or null. */
    public Long attrId(String key) {
        Object v = attrs.get(key);
        return v == null ? null : ((Number) v).longValue();
    }

    /** An attribute as text, or null. */
    public String attrString(String key) {
        Object v = attrs.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
