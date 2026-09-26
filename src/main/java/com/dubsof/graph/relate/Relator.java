package com.dubsof.graph.relate;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.AliasesDao;
import com.dubsof.graph.dao.ConsistencyChecks;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.IssuesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.RelationsDao;
import com.dubsof.graph.dao.row.AliasRow;
import com.dubsof.graph.dao.row.EntityFactRow;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dao.row.MentionRow;
import com.dubsof.graph.dao.row.MisfiledDocumentRow;
import com.dubsof.graph.dao.row.MultiCustomerDocumentRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.resolve.NameMatcher;

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

    /** Shortest alias the gazetteer searches for in free text (shorter ones match too much). */
    private static final int MIN_GAZETTEER_ALIAS = 6;

    private final RelationsDao relationsDao = new RelationsDao();
    private final FactsDao factsDao = new FactsDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final AliasesDao aliasesDao = new AliasesDao();
    private final EntitiesDao entitiesDao = new EntitiesDao();
    private final IssuesDao issuesDao = new IssuesDao();
    private final FilesDao filesDao = new FilesDao();
    private final ConsistencyChecks consistencyChecks = new ConsistencyChecks();

    private final Connection conn;

    public Relator(Connection conn) {
        this.conn = conn;
    }

    public static Map<String, Integer> run(Connection conn) throws Exception {
        return new Relator(conn).relate();
    }

    public Map<String, Integer> relate() throws Exception {
        relationsDao.deleteAll(conn);
        // group facts by (src entity, dst entity, rel), remembering every file that states them
        Map<String, Set<Long>> grouped = new LinkedHashMap<String, Set<Long>>();
        for (EntityFactRow f : factsDao.findBetweenEntities(conn)) {
            String k = f.srcEntityId + "|" + f.dstEntityId + "|" + f.rel.value();
            if (!grouped.containsKey(k)) {
                grouped.put(k, new TreeSet<Long>());
            }
            grouped.get(k).add(f.fileId);
        }
        for (Map.Entry<String, Set<Long>> e : grouped.entrySet()) {
            String[] p = e.getKey().split("\\|");
            upsert(Long.parseLong(p[0]), Long.parseLong(p[1]), RelationType.fromValue(p[2]), e.getValue());
        }
        int gazetteer = gazetteer();
        relationsDao.deriveShortcuts(conn);
        int pruned = pruneOrphans();
        checks();
        Db.commit(conn);
        Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
        stats.put("relations", (int) relationsDao.count(conn));
        stats.put("gazetteer_mentions", gazetteer);
        stats.put("orphan_documents_pruned", pruned);
        return stats;
    }

    /** Adds one relation (weight = number of files) and its evidence files. */
    private void upsert(long src, long dst, RelationType rel, Set<Long> fileIds) throws Exception {
        if (src == dst) {
            return;
        }
        long id = relationsDao.upsert(conn, src, dst, rel, Math.max(fileIds.size(), 1));
        for (Long f : fileIds) {
            relationsDao.addEvidence(conn, id, f);
        }
    }

    /**
     * Finds company/person names in free text the template parsers did not understand
     * (notes, e-mail bodies, letters), using every alias learned during resolution.
     */
    private int gazetteer() throws Exception {
        Map<String, Set<String>> names = new HashMap<String, Set<String>>();   // alias -> {"id|etype"}
        // the owner's letterhead is on everything, so the owner is left out
        for (AliasRow a : aliasesDao.findSearchable(conn, MIN_GAZETTEER_ALIAS, NameMatcher.companyKey(Config.ownerName))) {
            if (!names.containsKey(a.alias)) {
                names.put(a.alias, new HashSet<String>());
            }
            names.get(a.alias).add(a.entityId + "|" + a.entityType.value());
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
        for (MentionRow m : mentionsDao.findResolvedWithRole(conn, "self")) {
            docOfFile.put(m.fileId, m.entityId);
        }
        Map<Long, Set<Long>> linked = new HashMap<Long, Set<Long>>();
        for (MentionRow m : mentionsDao.findResolved(conn)) {
            if (!linked.containsKey(m.fileId)) {
                linked.put(m.fileId, new HashSet<Long>());
            }
            linked.get(m.fileId).add(m.entityId);
        }
        int added = 0;
        for (FileRow f : filesDao.findWithText(conn, FileStatus.OK)) {
            Long doc = docOfFile.get(f.id);
            if (doc == null) {
                continue;
            }
            Set<String> found = new TreeSet<String>();
            Matcher m = pattern.matcher(f.text);
            while (m.find()) {
                found.add(m.group(1));
            }
            for (String name : found) {
                String[] target = unique.get(name).split("\\|");
                long entity = Long.parseLong(target[0]);
                Set<Long> here = linked.containsKey(f.id) ? linked.get(f.id) : new HashSet<Long>();
                if (here.contains(entity)) {
                    continue;
                }
                mentionsDao.insertResolved(conn, f.id, EntityType.fromValue(target[1]), name, "text_mention", entity, "gazetteer", 0.6);
                Set<Long> one = new TreeSet<Long>();
                one.add(f.id);
                upsert(doc, entity, RelationType.MENTIONS, one);
                here.add(entity);
                linked.put(f.id, here);
                added++;
            }
        }
        return added;
    }

    /** Drops file documents that connect to nothing (stock photos, blank notes). */
    private int pruneOrphans() throws Exception {
        List<EntityRow> orphans = entitiesDao.findUnlinkedOfType(conn, EntityType.DOCUMENT);
        for (EntityRow e : orphans) {
            mentionsDao.unlinkEntity(conn, e.id, "orphan");
            aliasesDao.deleteByEntity(conn, e.id);
            issuesDao.deleteByEntity(conn, e.id);
            entitiesDao.delete(conn, e.id);
        }
        return orphans.size();
    }

    @SuppressWarnings("unchecked")
    private void checks() throws Exception {
        EntityRow owner = entitiesDao.findByTypeAndKey(conn, EntityType.COMPANY, NameMatcher.companyKey(Config.ownerName));
        long ownerId = owner == null ? 0 : owner.id;
        // referenced but never found as a file
        for (EntityRow d : consistencyChecks.findDocumentsWithoutFile(conn)) {
            entitiesDao.markMissing(conn, d.id);
            issue("missing_document", "info", d.key + " is referenced but no copy was found", d.id);
        }
        // one document number billed to two different customers
        for (MultiCustomerDocumentRow d : consistencyChecks.findMultiCustomerDocuments(conn)) {
            issue("conflict", "warn", d.documentKey + " is issued to more than one company: " + d.companies, d.documentId);
        }
        // copies of the same number disagreeing on totals / dates
        for (EntityRow d : consistencyChecks.findDocumentsWithConflicts(conn)) {
            Map<String, Object> conflicts = (Map<String, Object>) d.attrs.get("conflicts");
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, Object> c : conflicts.entrySet()) {
                detail.append(detail.length() == 0 ? "" : "; ").append(c.getKey()).append(' ').append(c.getValue());
            }
            issue("version_conflict", "warn", d.key + ": copies disagree on " + detail, d.id);
        }
        // document filed under one customer's folder but addressed to another
        for (MisfiledDocumentRow d : consistencyChecks.findMisfiledDocuments(conn, ownerId)) {
            issue("misfiled", "warn", d.documentKey + " sits in " + d.folderCompany + "'s folder but is addressed to " + d.addressedTo,
                    d.documentId);
        }
        // projects only known from a screenshot (not in the folder structure)
        for (EntityRow p : consistencyChecks.findUnfiledProjects(conn)) {
            issue("unfiled_project", "info", p.key + " appears in documents but has no project folder", p.id);
        }
    }

    private void issue(String kind, String severity, String detail, long entityId) throws Exception {
        issuesDao.insert(conn, kind, severity, detail, null, entityId);
    }
}
