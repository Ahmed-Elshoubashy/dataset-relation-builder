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
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.FileStatus;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 5: build the graph the explorer draws.
 *
 * After resolve, every mention points to an entity, but the links are still facts between
 * mentions inside single files. This stage turns them into relations between entities:
 * <ol>
 *   <li>facts become relations, each with the files that state it (its evidence);</li>
 *   <li>the gazetteer links documents to known names found in their free text;</li>
 *   <li>derived shortcut relations are added (person INVOLVED_IN project, ...);</li>
 *   <li>documents linked to nothing are removed;</li>
 *   <li>documents that are referenced but have no file are marked missing.</li>
 * </ol>
 * The relations table is rebuilt from scratch on every run.
 */
public class Relator {


    private final RelationsDao relationsDao = new RelationsDao();
    private final FactsDao factsDao = new FactsDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final AliasesDao aliasesDao = new AliasesDao();
    private final EntitiesDao entitiesDao = new EntitiesDao();
    private final FilesDao filesDao = new FilesDao();

    private final Connection conn;
    private final Dataset dataset;

    /** One link between two entities: "src REL dst". Used as a map key, so it has equals/hashCode. */
    private static class EntityLink {
        final long srcEntityId;
        final long dstEntityId;
        final RelationType rel;

        EntityLink(long srcEntityId, long dstEntityId, RelationType rel) {
            this.srcEntityId = srcEntityId;
            this.dstEntityId = dstEntityId;
            this.rel = rel;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof EntityLink)) {
                return false;
            }
            EntityLink that = (EntityLink) other;
            return srcEntityId == that.srcEntityId && dstEntityId == that.dstEntityId && rel == that.rel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(srcEntityId, dstEntityId, rel);
        }
    }

    /** The entity a known name refers to. Kept in sets, so it has equals/hashCode. */
    private static class NamedEntity {
        final long entityId;
        final EntityType type;

        NamedEntity(long entityId, EntityType type) {
            this.entityId = entityId;
            this.type = type;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof NamedEntity)) {
                return false;
            }
            NamedEntity that = (NamedEntity) other;
            return entityId == that.entityId && type == that.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(entityId, type);
        }
    }

    public Relator(Connection conn, Dataset dataset) {
        this.conn = conn;
        this.dataset = dataset;
    }

    public static Map<String, Integer> run(Connection conn, Dataset dataset) throws Exception {
        return new Relator(conn, dataset).relate();
    }

    /** Runs the five steps and returns the stage's summary (relations, gazetteer mentions, pruned documents). */
    public Map<String, Integer> relate() throws Exception {
        relationsDao.deleteAll(conn);
        createRelationsFromFacts();
        int gazetteerMentions = linkNamesInFreeText();
        relationsDao.deriveShortcuts(conn);
        int prunedDocuments = pruneOrphanDocuments();
        markMissingDocuments();
        Db.commit(conn);

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("relations", (int) relationsDao.count(conn));
        summary.put("gazetteer_mentions", gazetteerMentions);
        summary.put("orphan_documents_pruned", prunedDocuments);
        return summary;
    }

    // ================================================================ 1. facts -> relations

    /**
     * Turns every fact whose two mentions are resolved into a relation between their entities.
     * The same link stated in several files (or twice in one file, e.g. ISSUED_TO the bill-to company and
     * ISSUED_TO the filename company, both Acme) becomes one relation; its files are its evidence.
     */
    private void createRelationsFromFacts() throws Exception {
        // link -> files that state it, in the order the links are first seen
        Map<EntityLink, Set<Long>> filesOfLink = new LinkedHashMap<>();
        for (EntityFactRow fact : factsDao.findBetweenEntities(conn)) {
            EntityLink link = new EntityLink(fact.srcEntityId, fact.dstEntityId, fact.rel);
            if (!filesOfLink.containsKey(link)) {
                filesOfLink.put(link, new TreeSet<>());
            }
            filesOfLink.get(link).add(fact.fileId);
        }
        for (Map.Entry<EntityLink, Set<Long>> entry : filesOfLink.entrySet()) {
            EntityLink link = entry.getKey();
            saveRelation(link.srcEntityId, link.dstEntityId, link.rel, entry.getValue());
        }
    }

    /** Saves one relation (weight = number of files) and one evidence row per file. Self-links are skipped. */
    private void saveRelation(long srcEntityId, long dstEntityId, RelationType rel, Set<Long> fileIds) throws Exception {
        if (srcEntityId == dstEntityId) {
            return;
        }
        long relationId = relationsDao.upsert(conn, srcEntityId, dstEntityId, rel, Math.max(fileIds.size(), 1));
        for (Long fileId : fileIds) {
            relationsDao.addEvidence(conn, relationId, fileId);
        }
    }

    // ================================================================ 2. gazetteer

    /**
     * Finds company and person names in free text that no template parser understood
     * (notes, e-mail bodies, letters), using every spelling learned during resolve.
     * Each new name found adds a mention (role text_mention) and a relation "document MENTIONS entity".
     *
     * @return how many names were added
     */
    private int linkNamesInFreeText() throws Exception {
        Map<String, NamedEntity> entityByName = searchableNames();
        if (entityByName.isEmpty()) {
            return 0;
        }
        Pattern anyKnownName = namePattern(entityByName.keySet());

        // file id -> the file's own document entity (only files that are a document can MENTION something)
        Map<Long, Long> documentOfFile = new HashMap<>();
        for (MentionRow mention : mentionsDao.findResolvedWithRole(conn, MentionRole.SELF)) {
            documentOfFile.put(mention.fileId, mention.entityId);
        }
        // file id -> entities the file is already linked to (a name found again adds nothing)
        Map<Long, Set<Long>> entitiesOfFile = new HashMap<>();
        for (MentionRow mention : mentionsDao.findResolved(conn)) {
            if (!entitiesOfFile.containsKey(mention.fileId)) {
                entitiesOfFile.put(mention.fileId, new HashSet<>());
            }
            entitiesOfFile.get(mention.fileId).add(mention.entityId);
        }

        int added = 0;
        for (FileRow file : filesDao.findWithText(conn, FileStatus.OK)) {
            Long documentId = documentOfFile.get(file.id);
            if (documentId == null) {
                continue;
            }
            // every known name in the text, each once, in alphabetical order
            Set<String> namesFound = new TreeSet<>();
            Matcher matcher = anyKnownName.matcher(file.text);
            while (matcher.find()) {
                namesFound.add(matcher.group(1));
            }

            Set<Long> alreadyLinked = entitiesOfFile.containsKey(file.id) ? entitiesOfFile.get(file.id) : new HashSet<>();
            for (String name : namesFound) {
                NamedEntity named = entityByName.get(name);
                if (alreadyLinked.contains(named.entityId)) {
                    continue;
                }
                mentionsDao.insertResolved(conn, file.id, named.type, name, MentionRole.TEXT_MENTION,
                        named.entityId, "gazetteer", Config.GAZETTEER_CONFIDENCE);
                Set<Long> thisFile = new TreeSet<>();
                thisFile.add(file.id);
                saveRelation(documentId, named.entityId, RelationType.MENTIONS, thisFile);
                alreadyLinked.add(named.entityId);
                entitiesOfFile.put(file.id, alreadyLinked);
                added++;
            }
        }
        return added;
    }

    /**
     * Names worth searching for: every spelling of a company or person, at least Config.GAZETTEER_MIN_NAME_LENGTH long.
     * Left out: the owner (its name is on every letterhead) and names shared by two entities
     * (it would be unclear which one is meant).
     */
    private Map<String, NamedEntity> searchableNames() throws Exception {
        Map<String, Set<NamedEntity>> entitiesByName = new HashMap<>();
        String ownerKey = dataset.owner.isKnown() ? dataset.names.companyKey(dataset.owner.name) : "";
        for (AliasRow alias : aliasesDao.findSearchable(conn, Config.GAZETTEER_MIN_NAME_LENGTH, ownerKey)) {
            if (!entitiesByName.containsKey(alias.alias)) {
                entitiesByName.put(alias.alias, new HashSet<>());
            }
            entitiesByName.get(alias.alias).add(new NamedEntity(alias.entityId, alias.entityType));
        }
        Map<String, NamedEntity> unambiguous = new HashMap<>();
        for (Map.Entry<String, Set<NamedEntity>> entry : entitiesByName.entrySet()) {
            if (entry.getValue().size() == 1) {
                unambiguous.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        return unambiguous;
    }

    /**
     * One regular expression that matches any of the names as whole words: \b(name1|name2|...)\b.
     * Longest names first, so "Falcon Aerospace Components" wins over "Falcon Aerospace".
     */
    private static Pattern namePattern(Set<String> names) {
        List<String> longestFirst = new ArrayList<>(names);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder alternatives = new StringBuilder();
        for (String name : longestFirst) {
            if (alternatives.length() > 0) {
                alternatives.append('|');
            }
            alternatives.append(Pattern.quote(name));
        }
        return Pattern.compile("\\b(" + alternatives + ")\\b");
    }

    // ================================================================ 4. and 5. clean-up

    /**
     * Removes documents that are linked to nothing (stock photos, blank notes): their mentions
     * are unlinked (method "orphan"), their aliases deleted, then the entity itself.
     *
     * @return how many documents were removed
     */
    private int pruneOrphanDocuments() throws Exception {
        List<EntityRow> orphans = entitiesDao.findUnlinkedOfType(conn, EntityType.DOCUMENT);
        for (EntityRow orphan : orphans) {
            mentionsDao.unlinkEntity(conn, orphan.id, "orphan");
            aliasesDao.deleteByEntity(conn, orphan.id);
            entitiesDao.delete(conn, orphan.id);
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
