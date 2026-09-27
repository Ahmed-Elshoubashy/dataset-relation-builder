package com.dubsof.graph.api;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.AliasesDao;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.GraphQueries;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.MetaDao;
import com.dubsof.graph.dao.RelationsDao;
import com.dubsof.graph.dao.row.AliasRow;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.EvidenceRow;
import com.dubsof.graph.dao.row.FileMentionRow;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dao.row.NeighbourRow;
import com.dubsof.graph.dao.row.RelatedEntityRow;
import com.dubsof.graph.dao.row.RelationRow;
import com.dubsof.graph.dao.row.SourceRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.RelationType;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

    private final GraphQueries graphQueries = new GraphQueries();
    private final EntitiesDao entitiesDao = new EntitiesDao();
    private final FilesDao filesDao = new FilesDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final RelationsDao relationsDao = new RelationsDao();
    private final AliasesDao aliasesDao = new AliasesDao();
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
        out.put("sources", sourceMaps(graphQueries.findSources(conn, id, Config.MAX_EVIDENCE_FILES_SHOWN)));
        return out;
    }

    /**
     * Neighbourhood of {@code center} (projects and companies first, then the strongest links), or the
     * customer/project overview when center is null. A busy centre (a customer has 300+ links) cannot be drawn
     * whole: "hidden" says what was left out, per relation and entity type ("ISSUED_TO in: 212 documents"),
     * so the explorer can draw one group node for each instead of dropping them silently.
     */
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
        out.put("hidden", overview ? new ArrayList<Object>() : hiddenNeighbours(center, derived, allowed, nodes));
        return out;
    }

    /**
     * The centre's neighbours that are not drawn, counted per (relation, direction, entity type), largest first.
     * A neighbour linked in two ways is counted in both groups.
     */
    private List<Map<String, Object>> hiddenNeighbours(long center, boolean derived, List<String> allowed, Set<Long> drawn)
            throws Exception {
        Map<String, Set<Long>> byGroup = new LinkedHashMap<String, Set<Long>>();
        for (NeighbourRow row : graphQueries.findNeighbourRelations(conn, center, derived, allowed)) {
            if (!drawn.contains(row.id)) {
                String group = row.rel + "|" + row.dir + "|" + row.type;
                if (!byGroup.containsKey(group)) {
                    byGroup.put(group, new HashSet<Long>());
                }
                byGroup.get(group).add(row.id);
            }
        }
        List<Map<String, Object>> hidden = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Set<Long>> group : byGroup.entrySet()) {
            String[] parts = group.getKey().split("\\|");
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            g.put("rel", parts[0]);
            g.put("dir", parts[1]);
            g.put("type", parts[2]);
            g.put("count", group.getValue().size());
            hidden.add(g);
        }
        hidden.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
        return hidden;
    }

    /**
     * Why two entities are linked, for a click on an edge: the relation, its two ends, and the evidence. A stated
     * relation lists the files that state it, with how each end is written there ("ACME Corp" as bill_to); a derived
     * one (INVOLVED_IN, ...) has no files of its own, so it lists the documents it was inferred from and the rule.
     */
    public Map<String, Object> relation(long id) throws Exception {
        RelationRow r = graphQueries.findRelation(conn, id);
        if (r == null) {
            throw new ApiServer.ApiException(404, "Not Found");
        }
        Map<String, Object> out = relationMap(r);
        out.put("src_entity", summary(entitiesDao.findById(conn, r.src)));
        out.put("dst_entity", summary(entitiesDao.findById(conn, r.dst)));

        // one entry per file, with the mentions of both ends in it
        Map<Long, Map<String, Object>> files = new LinkedHashMap<Long, Map<String, Object>>();
        for (EvidenceRow row : graphQueries.findRelationEvidence(conn, id, Config.MAX_EVIDENCE_FILES_SHOWN)) {
            Map<String, Object> file = files.get(row.fileId);
            if (file == null) {
                file = new LinkedHashMap<String, Object>();
                file.put("id", row.fileId);
                file.put("path", row.path);
                file.put("kind", row.kind);
                file.put("status", row.status);
                file.put("mentions", new ArrayList<Map<String, Object>>());
                files.put(row.fileId, file);
            }
            if (row.entityId != null) {
                Map<String, Object> mention = new LinkedHashMap<String, Object>();
                mention.put("entity_id", row.entityId);
                mention.put("surface", row.surface);
                mention.put("role", row.role);
                mention.put("method", row.method);
                mention.put("confidence", row.confidence);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mentions = (List<Map<String, Object>>) file.get("mentions");
                mentions.add(mention);
            }
        }
        out.put("evidence", new ArrayList<Map<String, Object>>(files.values()));

        List<Map<String, Object>> via = new ArrayList<Map<String, Object>>();
        for (EntityRow document : graphQueries.findDerivedVia(conn, r, Config.MAX_EVIDENCE_FILES_SHOWN)) {
            via.add(summary(document));
        }
        out.put("via", via);
        out.put("rule", derivationRule(r.rel));
        return out;
    }

    /**
     * How two entities are connected: the shortest paths between them (up to CONNECTION_MAX_HOPS relations,
     * at most CONNECTION_MAX_PATHS of them), for "Find connection". The owner is never a step in between: it is
     * linked to nearly everything, so "both are linked to the owner" says nothing. Each path is a list of
     * relations, so every step can be explained like any other link.
     */
    public Map<String, Object> connection(long from, long to, boolean derived) throws Exception {
        EntityRow fromEntity = entitiesDao.findById(conn, from);
        EntityRow toEntity = entitiesDao.findById(conn, to);
        if (fromEntity == null || toEntity == null) {
            throw new ApiServer.ApiException(404, "Not Found");
        }
        // the graph, both directions: entity -> its relations
        Map<Long, List<RelationRow>> links = new HashMap<Long, List<RelationRow>>();
        for (RelationRow r : graphQueries.findAllRelations(conn, derived)) {
            links.computeIfAbsent(r.src, id -> new ArrayList<RelationRow>()).add(r);
            links.computeIfAbsent(r.dst, id -> new ArrayList<RelationRow>()).add(r);
        }
        Set<Long> avoided = new HashSet<Long>();
        for (EntityRow owner : graphQueries.findOwners(conn)) {
            if (owner.id != from && owner.id != to) {
                avoided.add(owner.id);
            }
        }

        // breadth first from "from", keeping every relation that reaches a node at its shortest distance
        Map<Long, Integer> distance = new HashMap<Long, Integer>();
        Map<Long, List<RelationRow>> reachedBy = new HashMap<Long, List<RelationRow>>();
        distance.put(from, 0);
        List<Long> frontier = new ArrayList<Long>();
        frontier.add(from);
        for (int hop = 1; hop <= Config.CONNECTION_MAX_HOPS && !frontier.isEmpty() && !distance.containsKey(to); hop++) {
            List<Long> next = new ArrayList<Long>();
            for (Long node : frontier) {
                for (RelationRow r : links.getOrDefault(node, new ArrayList<RelationRow>())) {
                    long other = r.src == node ? r.dst : r.src;
                    if (avoided.contains(other)) {
                        continue;
                    }
                    Integer known = distance.get(other);
                    if (known == null) {
                        distance.put(other, hop);
                        next.add(other);
                    }
                    if (known == null || known == hop) {
                        reachedBy.computeIfAbsent(other, id -> new ArrayList<RelationRow>()).add(r);
                    }
                }
            }
            frontier = next;
        }

        List<List<RelationRow>> paths = new ArrayList<List<RelationRow>>();
        if (distance.containsKey(to) && to != from) {
            collectPaths(to, from, reachedBy, new ArrayList<RelationRow>(), paths);
        }
        Set<Long> nodeIds = new LinkedHashSet<Long>();
        nodeIds.add(from);
        nodeIds.add(to);
        List<Map<String, Object>> pathMaps = new ArrayList<Map<String, Object>>();
        Map<Long, Map<String, Object>> edges = new LinkedHashMap<Long, Map<String, Object>>();
        for (List<RelationRow> path : paths) {
            List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
            for (RelationRow r : path) {
                nodeIds.add(r.src);
                nodeIds.add(r.dst);
                steps.add(relationMap(r));
                edges.put(r.id, relationMap(r));
            }
            pathMaps.add(Collections.<String, Object>singletonMap("steps", steps));
        }
        List<Map<String, Object>> nodeMaps = new ArrayList<Map<String, Object>>();
        for (EntityRow e : graphQueries.findEntitiesWithDegree(conn, nodeIds)) {
            nodeMaps.add(summary(e));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("from", from);
        out.put("to", to);
        out.put("hops", paths.isEmpty() ? null : paths.get(0).size());
        out.put("max_hops", Config.CONNECTION_MAX_HOPS);
        out.put("paths", pathMaps);
        out.put("nodes", nodeMaps);
        out.put("edges", new ArrayList<Map<String, Object>>(edges.values()));
        List<String> avoidedNames = new ArrayList<String>();
        for (Long id : avoided) {
            avoidedNames.add(entitiesDao.findById(conn, id).name);
        }
        out.put("avoided", avoidedNames);
        return out;
    }

    /**
     * Walks back from {@code node} to {@code start} along the relations that reached each node first, adding
     * every complete path (in order from start) until CONNECTION_MAX_PATHS are found.
     */
    private static void collectPaths(long node, long start, Map<Long, List<RelationRow>> reachedBy, List<RelationRow> tail,
                                     List<List<RelationRow>> paths) {
        if (paths.size() >= Config.CONNECTION_MAX_PATHS) {
            return;
        }
        if (node == start) {
            List<RelationRow> path = new ArrayList<RelationRow>(tail);
            Collections.reverse(path);
            paths.add(path);
            return;
        }
        for (RelationRow r : reachedBy.getOrDefault(node, new ArrayList<RelationRow>())) {
            long previous = r.src == node ? r.dst : r.src;
            tail.add(r);
            collectPaths(previous, start, reachedBy, tail, paths);
            tail.remove(tail.size() - 1);
        }
    }

    /** How a derived relation is inferred, in words; null for a relation stated in files. */
    private static String derivationRule(RelationType rel) {
        if (rel == RelationType.INVOLVED_IN) {
            return "The person authored, sent or received these documents of the project.";
        }
        if (rel == RelationType.USES_PRODUCT) {
            return "These documents of the project list or describe the product.";
        }
        if (rel == RelationType.PURCHASED_OR_QUOTED) {
            return "These documents issued to the company list the product.";
        }
        return null;
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
        return d;
    }

    // ------------------------------------------------------------------ rows -> JSON maps

    /** The short form of an entity used in lists and graph nodes. */
    public Map<String, Object> summary(EntityRow e) throws Exception {
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
