package com.dubsof.graph.api;

import com.dubsof.graph.db.Db;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The explorer's read-only queries over graph.db. Results are plain maps/lists, sent as JSON. */
public class GraphApi {

    private static final List<String> ETYPES = Arrays.asList("company", "project", "person", "document", "product");
    private static final String DEGREE = "(SELECT COUNT(*) FROM relations r WHERE r.src=e.id OR r.dst=e.id)";

    private final Connection conn;

    public GraphApi(Connection conn) {
        this.conn = conn;
    }

    public Map<String, Object> stats() throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("entities", counts("SELECT etype, COUNT(*) n FROM entities GROUP BY 1"));
        out.put("relations", Db.count(conn, "SELECT COUNT(*) FROM relations"));
        out.put("mentions", Db.count(conn, "SELECT COUNT(*) FROM mentions WHERE entity_id IS NOT NULL"));
        out.put("files", counts("SELECT status, COUNT(*) n FROM files GROUP BY 1"));
        out.put("issues", counts("SELECT kind, COUNT(*) n FROM issues GROUP BY 1"));
        out.put("resolution_methods", counts("SELECT etype || ':' || method, COUNT(*) n FROM mentions WHERE method IS NOT NULL GROUP BY 1"));
        out.put("meta", Db.meta(conn));
        return out;
    }

    public Map<String, Object> entities(String type, String q, String docType, int limit, int offset) throws Exception {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        if (type != null) {
            where.append(" AND e.etype=?");
            args.add(type);
        }
        if (docType != null) {
            where.append(" AND json_extract(e.attrs,'$.doc_type')=?");
            args.add(docType);
        }
        if (q != null && !q.isEmpty()) {
            where.append(" AND (e.name LIKE ? OR e.key LIKE ? OR EXISTS (SELECT 1 FROM aliases a WHERE a.entity_id=e.id AND a.alias LIKE ?))");
            String like = "%" + q + "%";
            args.addAll(Arrays.asList(like, like, like));
        }
        String filter = where.length() == 0 ? "" : " WHERE " + where.substring(5);
        long total = Db.count(conn, "SELECT COUNT(*) FROM entities e" + filter, args.toArray());
        List<Object> pageArgs = new ArrayList<Object>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : Db.query(conn, "SELECT e.*, " + DEGREE + " degree FROM entities e" + filter
                + " ORDER BY degree DESC, e.name LIMIT ? OFFSET ?", pageArgs.toArray())) {
            items.add(summary(r));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    public Map<String, Object> entity(long id) throws Exception {
        Map<String, Object> r = Db.one(conn, "SELECT e.*, " + DEGREE + " degree FROM entities e WHERE id=?", id);
        if (r == null) {
            throw new ApiServer.ApiException(404, "Not Found");
        }
        Map<String, Object> attrs = Json.readMap((String) r.get("attrs"));
        if (attrs.containsKey("company_id")) {   // show the organisation, not just its id
            attrs.put("company", Db.one(conn, "SELECT id, name FROM entities WHERE id=?", attrs.get("company_id")));
            attrs.remove("company_id");
        }
        List<Map<String, Object>> relations = Db.query(conn,
                "SELECT r.rel, r.weight, r.derived, 'out' dir, o.id, o.etype type, o.name, o.attrs"
                        + " FROM relations r JOIN entities o ON o.id=r.dst WHERE r.src=?"
                        + " UNION ALL"
                        + " SELECT r.rel, r.weight, r.derived, 'in' dir, o.id, o.etype type, o.name, o.attrs"
                        + " FROM relations r JOIN entities o ON o.id=r.src WHERE r.dst=?"
                        + " ORDER BY weight DESC", id, id);
        for (Map<String, Object> x : relations) {
            x.put("doc_type", Json.readMap((String) x.remove("attrs")).get("doc_type"));
        }
        Map<String, Object> out = summary(r);
        out.put("attrs", attrs);
        out.put("degree", r.get("degree"));
        out.put("aliases", Db.query(conn, "SELECT alias, method, confidence, count FROM aliases WHERE entity_id=? ORDER BY count DESC", id));
        out.put("relations", relations);
        out.put("sources", Db.query(conn,
                "SELECT f.id, f.path, f.kind, f.status, f.text_source, m.role, m.surface, m.method, m.confidence"
                        + " FROM mentions m JOIN files f ON f.id=m.file_id WHERE m.entity_id=?"
                        + " ORDER BY m.role='self' DESC, f.path LIMIT 400", id));
        out.put("issues", Db.query(conn,
                "SELECT DISTINCT i.kind, i.severity, i.detail, i.file_id FROM issues i"
                        + " WHERE i.entity_id=? OR i.file_id IN (SELECT file_id FROM mentions WHERE entity_id=? AND role='self')", id, id));
        return out;
    }

    /** Neighbourhood of {@code center} (strongest edges first), or the customer/project overview when center is null. */
    public Map<String, Object> graph(Long center, int depth, int limit, boolean derived, String types) throws Exception {
        List<String> allowed = types != null ? Arrays.asList(types.split(",")) : ETYPES;
        Set<Long> nodes = new LinkedHashSet<Long>();
        boolean overview = center == null;
        if (overview) {
            // customers and their filed projects; projects only seen in screenshots stay reachable from their customer
            for (Map<String, Object> r : Db.query(conn,
                    "SELECT id FROM entities WHERE etype='project' AND json_extract(attrs,'$.source')='folder'"
                            + " UNION SELECT r.src FROM relations r JOIN entities p ON p.id=r.dst"
                            + " WHERE r.rel='HAS_PROJECT' AND json_extract(p.attrs,'$.source')='folder'")) {
                nodes.add(Db.id(r.get("id")));
            }
        } else {
            nodes.add(center);
            Set<Long> frontier = new HashSet<Long>(nodes);
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < allowed.size(); i++) {
                marks.append(i == 0 ? "?" : ",?");
            }
            String sql = "SELECT CASE WHEN r.src=? THEN r.dst ELSE r.src END o, r.weight FROM relations r"
                    + " JOIN entities e ON e.id = CASE WHEN r.src=? THEN r.dst ELSE r.src END"
                    + " WHERE (r.src=? OR r.dst=?) AND (? OR r.derived=0) AND e.etype IN (" + marks + ")"
                    + " ORDER BY CASE e.etype WHEN 'project' THEN 0 WHEN 'company' THEN 1 WHEN 'product' THEN 2"
                    + " WHEN 'person' THEN 3 ELSE 4 END, r.weight DESC LIMIT ?";
            for (int d = 0; d < depth; d++) {
                Set<Long> next = new HashSet<Long>();
                for (Long n : frontier) {
                    int budget = limit - nodes.size();
                    if (budget <= 0) {
                        break;
                    }
                    List<Object> args = new ArrayList<Object>(Arrays.<Object>asList(n, n, n, n, derived ? 1 : 0));
                    args.addAll(allowed);
                    args.add(budget);
                    for (Map<String, Object> r : Db.query(conn, sql, args.toArray())) {
                        long o = Db.id(r.get("o"));
                        if (nodes.add(o)) {
                            next.add(o);
                        }
                    }
                }
                frontier = next;
            }
        }
        List<Map<String, Object>> entityRows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        if (!nodes.isEmpty()) {
            StringBuilder in = new StringBuilder();
            for (Long n : nodes) {
                in.append(in.length() == 0 ? "" : ",").append(n);   // ids are numbers we produced ourselves
            }
            for (Map<String, Object> r : Db.query(conn, "SELECT e.*, " + DEGREE + " degree FROM entities e WHERE id IN (" + in + ")")) {
                entityRows.add(summary(r));
            }
            for (Map<String, Object> e : Db.query(conn, "SELECT id, src, dst, rel, weight, derived FROM relations"
                    + " WHERE src IN (" + in + ") AND dst IN (" + in + ") AND (? OR derived=0)", derived ? 1 : 0)) {
                if (!overview || "HAS_PROJECT".equals(e.get("rel"))) {
                    edges.add(e);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("nodes", entityRows);
        out.put("edges", edges);
        out.put("center", center);
        return out;
    }

    public List<Map<String, Object>> issues(String kind, int limit) throws Exception {
        String sql = "SELECT i.*, f.path, e.name entity_name, e.etype entity_type FROM issues i"
                + " LEFT JOIN files f ON f.id=i.file_id LEFT JOIN entities e ON e.id=i.entity_id";
        String order = " ORDER BY CASE i.severity WHEN 'error' THEN 0 WHEN 'warn' THEN 1 ELSE 2 END, i.kind LIMIT ?";
        return kind != null ? Db.query(conn, sql + " WHERE i.kind=?" + order, kind, limit) : Db.query(conn, sql + order, limit);
    }

    /** Probable aliases/abbreviations of whatever entity best matches q (for a future chat/MCP layer). */
    public Map<String, Object> aliases(String q) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Object hit = Db.scalar(conn, "SELECT e.id FROM entities e LEFT JOIN aliases a ON a.entity_id=e.id"
                + " WHERE e.name LIKE ? OR a.alias LIKE ? GROUP BY e.id ORDER BY COUNT(*) DESC LIMIT 1", "%" + q + "%", "%" + q + "%");
        if (hit == null) {
            out.put("entity", null);
            out.put("aliases", new ArrayList<Object>());
            return out;
        }
        Map<String, Object> e = entity(Db.id(hit));
        Map<String, Object> brief = new LinkedHashMap<String, Object>();
        brief.put("id", e.get("id"));
        brief.put("name", e.get("name"));
        brief.put("type", e.get("type"));
        out.put("entity", brief);
        out.put("aliases", e.get("aliases"));
        return out;
    }

    public Map<String, Object> file(long id) throws Exception {
        Map<String, Object> d = Db.one(conn, "SELECT id, path, kind, status, text_source, size, error, text FROM files WHERE id=?", id);
        if (d == null) {
            throw new ApiServer.ApiException(404, "Not Found");
        }
        d.put("mentions", Db.query(conn, "SELECT m.surface, m.role, m.method, m.confidence, e.id entity_id, e.name entity_name, e.etype type"
                + " FROM mentions m LEFT JOIN entities e ON e.id=m.entity_id WHERE m.file_id=?", id));
        d.put("issues", Db.query(conn, "SELECT kind, severity, detail FROM issues WHERE file_id=?", id));
        return d;
    }

    // ------------------------------------------------------------------ helpers

    /** The short form of an entity used in lists and graph nodes. */
    private Map<String, Object> summary(Map<String, Object> r) throws Exception {
        Map<String, Object> attrs = Json.readMap((String) r.get("attrs"));
        String type = (String) r.get("etype");
        Object sub = null;
        if (type.equals("document")) {
            List<String> parts = new ArrayList<String>();
            if (attrs.get("doc_type") != null) {
                parts.add(((String) attrs.get("doc_type")).replace("_", " "));
            }
            if (attrs.get("date") != null) {
                parts.add(String.valueOf(attrs.get("date")));
            }
            sub = parts.isEmpty() ? null : join(parts, " · ");
        } else if ((type.equals("person") || type.equals("project")) && attrs.get("company_id") != null) {
            sub = Db.scalar(conn, "SELECT name FROM entities WHERE id=?", attrs.get("company_id"));
        } else if (type.equals("project")) {
            sub = attrs.get("title") == null ? null : attrs.get("status") != null ? attrs.get("status") : "project";
        } else if (type.equals("company")) {
            sub = attrs.get("role");
        } else if (type.equals("product")) {
            sub = attrs.get("code");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", r.get("id"));
        out.put("type", type);
        out.put("name", r.get("name"));
        out.put("key", r.get("key"));
        out.put("subtitle", sub);
        out.put("doc_type", attrs.get("doc_type"));
        Object missing = attrs.get("missing");   // set to 1 by the relate stage
        out.put("missing", missing != null && !missing.equals(0) && !Boolean.FALSE.equals(missing));
        if (r.containsKey("degree")) {
            out.put("degree", r.get("degree"));
        }
        return out;
    }

    private Map<String, Object> counts(String sql) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map<String, Object> r : Db.query(conn, sql)) {
            Object[] values = r.values().toArray();
            out.put(String.valueOf(values[0]), values[1]);
        }
        return out;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(i == 0 ? "" : sep).append(parts.get(i));
        }
        return sb.toString();
    }
}
