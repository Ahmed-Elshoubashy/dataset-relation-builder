package com.dubsof.graph.relate;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.AliasesDao;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.RelationsDao;
import com.dubsof.graph.dao.row.AliasRow;
import com.dubsof.graph.dao.row.EntityFactRow;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dao.row.MentionRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.MentionRole;
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
    private final FilesDao filesDao = new FilesDao();

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
        markMissingDocuments();
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
        for (MentionRow m : mentionsDao.findResolvedWithRole(conn, MentionRole.SELF)) {
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
                mentionsDao.insertResolved(conn, f.id, EntityType.fromValue(target[1]), name, MentionRole.TEXT_MENTION, entity, "gazetteer", 0.6);
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
            entitiesDao.delete(conn, e.id);
        }
        return orphans.size();
    }

    /** Documents that other files reference (e.g. "Quote Ref: QUO-5238") but that no file is: marked missing. */
    private void markMissingDocuments() throws Exception {
        for (EntityRow document : entitiesDao.findReferencedDocumentsWithoutFile(conn)) {
            entitiesDao.markMissing(conn, document.id);
        }
    }
}
