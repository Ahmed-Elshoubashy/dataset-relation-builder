package com.dubsof.graph.relate;

import com.dubsof.graph.Config;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.resolve.NameMatcher;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 5: lift mention-level facts to entity-level relations (with the files that prove them),
 * add derived shortcut relations, and run cross-document consistency checks.
 */
public class Relator {

    private final Connection conn;

    public Relator(Connection conn) {
        this.conn = conn;
    }

    public static Map<String, Integer> run(Connection conn) throws Exception {
        return new Relator(conn).relate();
    }

    public Map<String, Integer> relate() throws Exception {
        Db.update(conn, "DELETE FROM relation_evidence");
        Db.update(conn, "DELETE FROM relations");
        // group facts by (src entity, dst entity, rel), remembering every file that states them
        Map<String, Set<Long>> grouped = new LinkedHashMap<String, Set<Long>>();
        for (Map<String, Object> r : Db.query(conn,
                "SELECT f.file_id, ms.entity_id s, f.rel, md.entity_id d FROM facts f"
                        + " JOIN mentions ms ON ms.id=f.src JOIN mentions md ON md.id=f.dst"
                        + " WHERE ms.entity_id IS NOT NULL AND md.entity_id IS NOT NULL ORDER BY f.id")) {
            String k = r.get("s") + "|" + r.get("d") + "|" + r.get("rel");
            if (!grouped.containsKey(k)) {
                grouped.put(k, new TreeSet<Long>());
            }
            grouped.get(k).add(Db.id(r.get("file_id")));
        }
        for (Map.Entry<String, Set<Long>> e : grouped.entrySet()) {
            String[] p = e.getKey().split("\\|");
            upsert(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], e.getValue());
        }
        int gazetteer = gazetteer();
        derive();
        int pruned = pruneOrphans();
        checks();
        Db.commit(conn);
        Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
        stats.put("relations", (int) Db.count(conn, "SELECT COUNT(*) FROM relations"));
        stats.put("gazetteer_mentions", gazetteer);
        stats.put("orphan_documents_pruned", pruned);
        return stats;
    }

    private void upsert(long src, long dst, String rel, Set<Long> fileIds) throws Exception {
        if (src == dst) {
            return;
        }
        Db.update(conn, "INSERT INTO relations (src, dst, rel, weight, derived) VALUES (?,?,?,?,0)"
                + " ON CONFLICT(src, dst, rel) DO UPDATE SET weight = weight + excluded.weight",
                src, dst, rel, Math.max(fileIds.size(), 1));
        long id = Db.count(conn, "SELECT id FROM relations WHERE src=? AND dst=? AND rel=?", src, dst, rel);
        for (Long f : fileIds) {
            Db.update(conn, "INSERT OR IGNORE INTO relation_evidence VALUES (?,?)", id, f);
        }
    }

    /**
     * Finds company/person names in free text the template parsers did not understand
     * (notes, e-mail bodies, letters), using every alias learned during resolution.
     */
    private int gazetteer() throws Exception {
        Map<String, Set<String>> names = new HashMap<String, Set<String>>();   // alias -> {"id|etype"}
        for (Map<String, Object> r : Db.query(conn,
                "SELECT a.entity_id, a.alias, e.etype FROM aliases a JOIN entities e ON e.id=a.entity_id"
                        + " WHERE e.etype IN ('company','person') AND length(a.alias) >= 6 AND a.alias NOT LIKE '%.%.%'"
                        + " AND e.key != ?", NameMatcher.companyKey(Config.ownerName))) {   // the owner's letterhead is on everything
            String alias = (String) r.get("alias");
            if (!names.containsKey(alias)) {
                names.put(alias, new HashSet<String>());
            }
            names.get(alias).add(r.get("entity_id") + "|" + r.get("etype"));
        }
        final Map<String, String> unique = new HashMap<String, String>();
        for (Map.Entry<String, Set<String>> e : names.entrySet()) {
            if (e.getValue().size() == 1) {
                unique.put(e.getKey(), e.getValue().iterator().next());
            }
        }
        if (unique.isEmpty()) {
            return 0;
        }
        List<String> sorted = new ArrayList<String>(unique.keySet());
        Collections.sort(sorted, new Comparator<String>() {   // longest names first, so they win over their prefixes
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        StringBuilder alternatives = new StringBuilder();
        for (String n : sorted) {
            alternatives.append(alternatives.length() == 0 ? "" : "|").append(Pattern.quote(n));
        }
        Pattern pattern = Pattern.compile("\\b(" + alternatives + ")\\b");

        Map<Long, Long> docOfFile = new HashMap<Long, Long>();
        for (Map<String, Object> r : Db.query(conn, "SELECT file_id, entity_id FROM mentions WHERE role='self' AND entity_id IS NOT NULL")) {
            docOfFile.put(Db.id(r.get("file_id")), Db.id(r.get("entity_id")));
        }
        Map<Long, Set<Long>> linked = new HashMap<Long, Set<Long>>();
        for (Map<String, Object> r : Db.query(conn, "SELECT file_id, entity_id FROM mentions WHERE entity_id IS NOT NULL")) {
            long f = Db.id(r.get("file_id"));
            if (!linked.containsKey(f)) {
                linked.put(f, new HashSet<Long>());
            }
            linked.get(f).add(Db.id(r.get("entity_id")));
        }
        int added = 0;
        for (Map<String, Object> f : Db.query(conn, "SELECT id, text FROM files WHERE status=? AND text IS NOT NULL", FileStatus.OK.value())) {
            long fileId = Db.id(f.get("id"));
            Long doc = docOfFile.get(fileId);
            if (doc == null) {
                continue;
            }
            Set<String> found = new TreeSet<String>();
            Matcher m = pattern.matcher((String) f.get("text"));
            while (m.find()) {
                found.add(m.group(1));
            }
            for (String name : found) {
                String[] target = unique.get(name).split("\\|");
                long entity = Long.parseLong(target[0]);
                Set<Long> here = linked.containsKey(fileId) ? linked.get(fileId) : new HashSet<Long>();
                if (here.contains(entity)) {
                    continue;
                }
                Db.update(conn, "INSERT INTO mentions (file_id, etype, surface, role, attrs, entity_id, method, confidence)"
                        + " VALUES (?,?,?,?,?,?,?,?)", fileId, target[1], name, "text_mention", "{}", entity, "gazetteer", 0.6);
                Set<Long> one = new TreeSet<Long>();
                one.add(fileId);
                upsert(doc, entity, "MENTIONS", one);
                here.add(entity);
                linked.put(fileId, here);
                added++;
            }
        }
        return added;
    }

    /** Shortcuts that answer common questions without multi-hop traversal. */
    private void derive() throws Exception {
        // person -> project, through any document they authored / sent / received / were addressed on
        Db.update(conn, "INSERT OR IGNORE INTO relations (src, dst, rel, weight, derived)"
                + " SELECT p, proj, 'INVOLVED_IN', COUNT(*), 1 FROM ("
                + "   SELECT r.src p, h.src proj FROM relations r JOIN relations h ON h.dst=r.dst AND h.rel='HAS_DOCUMENT'"
                + "    WHERE r.rel IN ('AUTHORED','SENT','RECEIVED')"
                + "   UNION ALL"
                + "   SELECT r.dst p, h.src proj FROM relations r JOIN relations h ON h.dst=r.src AND h.rel='HAS_DOCUMENT'"
                + "    WHERE r.rel='ATTENTION_OF'"
                + " ) GROUP BY p, proj");
        // project -> product, through line items, specs and datasheets filed in the project
        Db.update(conn, "INSERT OR IGNORE INTO relations (src, dst, rel, weight, derived)"
                + " SELECT h.src, r.dst, 'USES_PRODUCT', COUNT(*), 1 FROM relations h"
                + "   JOIN relations r ON r.src=h.dst AND r.rel IN ('LISTS_PRODUCT','DESCRIBES')"
                + "   JOIN entities e ON e.id=r.dst AND e.etype='product'"
                + " WHERE h.rel='HAS_DOCUMENT' GROUP BY h.src, r.dst");
        // company -> product (what each customer has bought or been quoted)
        Db.update(conn, "INSERT OR IGNORE INTO relations (src, dst, rel, weight, derived)"
                + " SELECT i.dst, l.dst, 'PURCHASED_OR_QUOTED', COUNT(*), 1 FROM relations i"
                + "   JOIN relations l ON l.src=i.src AND l.rel='LISTS_PRODUCT'"
                + " WHERE i.rel='ISSUED_TO' GROUP BY i.dst, l.dst");
    }

    /** Drops file documents that connect to nothing (stock photos, blank notes). */
    private int pruneOrphans() throws Exception {
        List<Map<String, Object>> orphans = Db.query(conn, "SELECT e.id FROM entities e WHERE e.etype='document'"
                + " AND NOT EXISTS (SELECT 1 FROM relations r WHERE r.src=e.id OR r.dst=e.id)");
        for (Map<String, Object> r : orphans) {
            Object id = r.get("id");
            Db.update(conn, "UPDATE mentions SET entity_id=NULL, method='orphan' WHERE entity_id=?", id);
            Db.update(conn, "DELETE FROM aliases WHERE entity_id=?", id);
            Db.update(conn, "DELETE FROM issues WHERE entity_id=?", id);
            Db.update(conn, "DELETE FROM entities WHERE id=?", id);
        }
        return orphans.size();
    }

    @SuppressWarnings("unchecked")
    private void checks() throws Exception {
        long owner = Db.count(conn, "SELECT id FROM entities WHERE etype='company' AND key=?", NameMatcher.companyKey(Config.ownerName));
        // referenced but never found as a file
        for (Map<String, Object> r : Db.query(conn, "SELECT e.id, e.key FROM entities e WHERE e.etype='document' AND NOT EXISTS"
                + " (SELECT 1 FROM mentions m WHERE m.entity_id=e.id AND m.role='self')")) {
            Db.update(conn, "UPDATE entities SET attrs=json_set(attrs,'$.missing',1) WHERE id=?", r.get("id"));
            issue("missing_document", "info", r.get("key") + " is referenced but no copy was found", r.get("id"));
        }
        // one document number billed to two different customers
        for (Map<String, Object> r : Db.query(conn, "SELECT r.src, e.key, GROUP_CONCAT(DISTINCT c.name) cs, COUNT(DISTINCT r.dst) n"
                + " FROM relations r JOIN entities e ON e.id=r.src JOIN entities c ON c.id=r.dst"
                + " WHERE r.rel='ISSUED_TO' GROUP BY r.src HAVING n > 1")) {
            issue("conflict", "warn", r.get("key") + " is issued to more than one company: " + r.get("cs"), r.get("src"));
        }
        // copies of the same number disagreeing on totals / dates
        for (Map<String, Object> r : Db.query(conn, "SELECT id, key, attrs FROM entities WHERE etype='document' AND attrs LIKE '%conflicts%'")) {
            Map<String, Object> conflicts = (Map<String, Object>) Json.readMap((String) r.get("attrs")).get("conflicts");
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, Object> c : conflicts.entrySet()) {
                detail.append(detail.length() == 0 ? "" : "; ").append(c.getKey()).append(' ').append(c.getValue());
            }
            issue("version_conflict", "warn", r.get("key") + ": copies disagree on " + detail, r.get("id"));
        }
        // document filed under one customer's folder but addressed to another
        for (Map<String, Object> r : Db.query(conn, "SELECT DISTINCT d.id, d.key, fc.name folder_co, c.name billed"
                + " FROM relations fr JOIN entities d ON d.id=fr.dst"
                + " JOIN relations h ON h.src IN (SELECT src FROM relations WHERE rel='HAS_PROJECT') AND h.rel='HAS_PROJECT' AND h.dst=fr.src"
                + " JOIN entities fc ON fc.id=h.src"
                + " JOIN relations i ON i.src=d.id AND i.rel IN ('ISSUED_TO','ADDRESSED_TO')"
                + " JOIN entities c ON c.id=i.dst"
                + " WHERE fr.rel='HAS_DOCUMENT' AND i.dst != h.src AND i.dst != ?", owner)) {
            issue("misfiled", "warn", r.get("key") + " sits in " + r.get("folder_co") + "'s folder but is addressed to " + r.get("billed"), r.get("id"));
        }
        // projects only known from a screenshot (not in the folder structure)
        for (Map<String, Object> r : Db.query(conn, "SELECT id, key FROM entities WHERE etype='project' AND json_extract(attrs,'$.source')!='folder'")) {
            issue("unfiled_project", "info", r.get("key") + " appears in documents but has no project folder", r.get("id"));
        }
    }

    private void issue(String kind, String severity, String detail, Object entityId) throws Exception {
        Db.update(conn, "INSERT INTO issues (kind, severity, detail, entity_id) VALUES (?,?,?,?)", kind, severity, detail, entityId);
    }
}
