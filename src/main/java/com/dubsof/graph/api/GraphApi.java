package com.dubsof.graph.api;

import com.dubsof.graph.dao.AliasesDao;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.GraphQueries;
import com.dubsof.graph.dao.IssuesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.MetaDao;
import com.dubsof.graph.dao.RelationsDao;
import com.dubsof.graph.dao.row.AliasRow;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.FileMentionRow;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dao.row.IssueRow;
import com.dubsof.graph.dao.row.RelatedEntityRow;
import com.dubsof.graph.dao.row.RelationRow;
import com.dubsof.graph.dao.row.SourceRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.RelationType;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The explorer's read-only queries over graph.db. Rows from the DAOs are turned into plain maps and sent as JSON. */
public class GraphApi {

    private static final List<String> ETYPES = new ArrayList<String>();

    static {
        for (EntityType t : EntityType.values()) {
            ETYPES.add(t.value());
        }
    }
    /** How many evidence files the details panel lists. */
    private static final int MAX_SOURCES = 400;

    private final GraphQueries graphQueries = new GraphQueries();
    private final EntitiesDao entitiesDao = new EntitiesDao();
    private final FilesDao filesDao = new FilesDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final RelationsDao relationsDao = new RelationsDao();
    private final AliasesDao aliasesDao = new AliasesDao();
    private final IssuesDao issuesDao = new IssuesDao();
    private final MetaDao metaDao = new MetaDao();

    private final Connection conn;

    public GraphApi(Connection conn) {
        this.conn = conn;
    }

    public Map<String, Object> stats() throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("entities", entitiesDao.countsByType(conn));
        out.put("relations", relationsDao.count(conn));
        out.put("mentions", mentionsDao.countResolved(conn));
        out.put("files", filesDao.countsByStatus(conn));
        out.put("issues", issuesDao.countsByKind(conn));
        out.put("resolution_methods", mentionsDao.countsByMethod(conn));
        out.put("meta", metaDao.findAll(conn));
        return out;
    }

    public Map<String, Object> entities(String type, String q, String docType, int limit, int offset) throws Exception {
        String search = q == null || q.isEmpty() ? null : q;
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (EntityRow e : graphQueries.searchEntities(conn, type, docType, search, limit, offset)) {
            items.add(summary(e));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", graphQueries.countEntities(conn, type, docType, search));
        out.put("items", items);
        return out;
    }

    public Map<String, Object> entity(long id) throws Exception {
        EntityRow e = graphQueries.findEntityWithDegree(conn, id);
        if (e == null) {
            throw new ApiServer.ApiException(404, "Not Found");
        }
        Map<String, Object> attrs = e.attrs;
        if (attrs.containsKey("company_id")) {   // show the organisation, not just its id
            EntityRow company = entitiesDao.findById(conn, ((Number) attrs.get("company_id")).longValue());
            Map<String, Object> brief = null;
            if (company != null) {
                brief = new LinkedHashMap<String, Object>();
                brief.put("id", company.id);
                brief.put("name", company.name);
            }
            attrs.put("company", brief);
            attrs.remove("company_id");
        }
        Map<String, Object> out = summary(e);
        out.put("attrs", attrs);
        out.put("degree", e.degree);
        out.put("aliases", aliasMaps(aliasesDao.findByEntity(conn, id)));
        out.put("relations", relatedMaps(graphQueries.findRelated(conn, id)));
        out.put("sources", sourceMaps(graphQueries.findSources(conn, id, MAX_SOURCES)));
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        for (IssueRow i : graphQueries.findEntityIssues(conn, id)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", i.kind);
            m.put("severity", i.severity);
            m.put("detail", i.detail);
            m.put("file_id", i.fileId);
            issues.add(m);
        }
        out.put("issues", issues);
        return out;
    }

    /** Neighbourhood of {@code center} (strongest edges first), or the customer/project overview when center is null. */
    public Map<String, Object> graph(Long center, int depth, int limit, boolean derived, String types) throws Exception {
        List<String> allowed = types != null ? Arrays.asList(types.split(",")) : ETYPES;
        Set<Long> nodes = new LinkedHashSet<Long>();
        boolean overview = center == null;
        if (overview) {
            nodes.addAll(graphQueries.findOverviewNodeIds(conn));
        } else {
            nodes.add(center);
            Set<Long> frontier = new HashSet<Long>(nodes);
            for (int d = 0; d < depth; d++) {
                Set<Long> next = new HashSet<Long>();
                for (Long n : frontier) {
                    int budget = limit - nodes.size();
                    if (budget <= 0) {
                        break;
                    }
                    for (Long other : graphQueries.findNeighbourIds(conn, n, derived, allowed, budget)) {
                        if (nodes.add(other)) {
                            next.add(other);
                        }
                    }
                }
                frontier = next;
            }
        }
        List<Map<String, Object>> nodeMaps = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> edges = new ArrayList<Map<String, Object>>();
        if (!nodes.isEmpty()) {
            for (EntityRow e : graphQueries.findEntitiesWithDegree(conn, nodes)) {
                nodeMaps.add(summary(e));
            }
            for (RelationRow r : graphQueries.findRelationsAmong(conn, nodes, derived)) {
                if (!overview || r.rel == RelationType.HAS_PROJECT) {   // the overview only draws customer -> project
                    edges.add(relationMap(r));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("nodes", nodeMaps);
        out.put("edges", edges);
        out.put("center", center);
        return out;
    }

    public List<Map<String, Object>> issues(String kind, int limit) throws Exception {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (IssueRow i : graphQueries.findIssues(conn, kind, limit)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", i.id);
            m.put("kind", i.kind);
            m.put("severity", i.severity);
            m.put("detail", i.detail);
            m.put("file_id", i.fileId);
            m.put("entity_id", i.entityId);
            m.put("path", i.path);
            m.put("entity_name", i.entityName);
            m.put("entity_type", i.entityType);
            out.add(m);
        }
        return out;
    }

    /** Probable aliases/abbreviations of whatever entity best matches q (for a future chat/MCP layer). */
    public Map<String, Object> aliases(String q) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Long hit = graphQueries.findBestMatchId(conn, q);
        if (hit == null) {
            out.put("entity", null);
            out.put("aliases", new ArrayList<Object>());
            return out;
        }
        Map<String, Object> e = entity(hit);
        Map<String, Object> brief = new LinkedHashMap<String, Object>();
        brief.put("id", e.get("id"));
        brief.put("name", e.get("name"));
        brief.put("type", e.get("type"));
        out.put("entity", brief);
        out.put("aliases", e.get("aliases"));
        return out;
    }

    public Map<String, Object> file(long id) throws Exception {
        FileRow f = filesDao.findById(conn, id);
        if (f == null) {
            throw new ApiServer.ApiException(404, "Not Found");
        }
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("id", f.id);
        d.put("path", f.path);
        d.put("kind", f.kind.value());
        d.put("status", f.status.value());
        d.put("text_source", f.textSource == null ? null : f.textSource.value());
        d.put("size", f.size);
        d.put("error", f.error);
        d.put("text", f.text);
        List<Map<String, Object>> mentions = new ArrayList<Map<String, Object>>();
        for (FileMentionRow m : graphQueries.findFileMentions(conn, id)) {
            Map<String, Object> mm = new LinkedHashMap<String, Object>();
            mm.put("surface", m.surface);
            mm.put("role", m.role);
            mm.put("method", m.method);
            mm.put("confidence", m.confidence);
            mm.put("entity_id", m.entityId);
            mm.put("entity_name", m.entityName);
            mm.put("type", m.entityType);
            mentions.add(mm);
        }
        d.put("mentions", mentions);
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        for (IssueRow i : issuesDao.findByFile(conn, id)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", i.kind);
            m.put("severity", i.severity);
            m.put("detail", i.detail);
            issues.add(m);
        }
        d.put("issues", issues);
        return d;
    }

    // ------------------------------------------------------------------ rows -> JSON maps

    /** The short form of an entity used in lists and graph nodes. */
    private Map<String, Object> summary(EntityRow e) throws Exception {
        Map<String, Object> attrs = e.attrs;
        Object sub = null;
        if (e.etype == EntityType.DOCUMENT) {
            List<String> parts = new ArrayList<String>();
            if (attrs.get("doc_type") != null) {
                parts.add(((String) attrs.get("doc_type")).replace("_", " "));
            }
            if (attrs.get("date") != null) {
                parts.add(String.valueOf(attrs.get("date")));
            }
            sub = parts.isEmpty() ? null : join(parts, " · ");
        } else if ((e.etype == EntityType.PERSON || e.etype == EntityType.PROJECT) && attrs.get("company_id") != null) {
            EntityRow company = entitiesDao.findById(conn, ((Number) attrs.get("company_id")).longValue());
            sub = company == null ? null : company.name;
        } else if (e.etype == EntityType.PROJECT) {
            sub = attrs.get("title") == null ? null : attrs.get("status") != null ? attrs.get("status") : "project";
        } else if (e.etype == EntityType.COMPANY) {
            sub = attrs.get("role");
        } else if (e.etype == EntityType.PRODUCT) {
            sub = attrs.get("code");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", e.id);
        out.put("type", e.etype.value());
        out.put("name", e.name);
        out.put("key", e.key);
        out.put("subtitle", sub);
        out.put("doc_type", attrs.get("doc_type"));
        Object missing = attrs.get("missing");   // set to 1 by the relate stage
        out.put("missing", missing != null && !missing.equals(0) && !Boolean.FALSE.equals(missing));
        if (e.degree != null) {
            out.put("degree", e.degree);
        }
        return out;
    }

    private static List<Map<String, Object>> aliasMaps(List<AliasRow> rows) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (AliasRow a : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("alias", a.alias);
            m.put("method", a.method);
            m.put("confidence", a.confidence);
            m.put("count", a.count);
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> relatedMaps(List<RelatedEntityRow> rows) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (RelatedEntityRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("rel", r.rel);
            m.put("weight", r.weight);
            m.put("derived", r.derived);
            m.put("dir", r.dir);
            m.put("id", r.id);
            m.put("type", r.type);
            m.put("name", r.name);
            m.put("doc_type", r.docType);
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> sourceMaps(List<SourceRow> rows) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SourceRow s : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", s.fileId);
            m.put("path", s.path);
            m.put("kind", s.kind);
            m.put("status", s.status);
            m.put("text_source", s.textSource);
            m.put("role", s.role);
            m.put("surface", s.surface);
            m.put("method", s.method);
            m.put("confidence", s.confidence);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> relationMap(RelationRow r) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", r.id);
        m.put("src", r.src);
        m.put("dst", r.dst);
        m.put("rel", r.rel.value());
        m.put("weight", r.weight);
        m.put("derived", r.derived);
        return m;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(i == 0 ? "" : sep).append(parts.get(i));
        }
        return sb.toString();
    }
}
