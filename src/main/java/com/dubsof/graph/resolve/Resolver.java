package com.dubsof.graph.resolve;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.AliasesDao;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.IssuesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.FactRow;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dao.row.MentionRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.resolve.NameMatcher.Match;
import com.dubsof.graph.util.Text;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Stage 4: cluster mentions into entities.
 *
 * Order matters: companies first (people and projects use the resolved company as
 * context), then projects, people, documents and products. Every mention ends up with
 * entity_id + method + confidence, so every link can explain why two spellings were
 * judged to be the same thing.
 */
public class Resolver {

    /** Relations whose other end tells which customer (or product) a document belongs to. */
    private static final List<RelationType> COUNTERPARTY_RELATIONS = java.util.Arrays.asList(
            RelationType.ISSUED_TO, RelationType.ADDRESSED_TO, RelationType.PARTY_TO, RelationType.DESCRIBES);

    static final double ACCEPT = 0.80;          // auto-merge at or above
    static final double GRAY = 0.65;            // between GRAY and ACCEPT: ask the adjudicator
    static final double AMBIGUOUS_MARGIN = 0.03;

    /** Most to least trustworthy source of a company name. */
    private static final List<String> COMPANY_ROLE_ORDER = java.util.Arrays.asList(
            "folder", "implied_owner", "bill_to", "contract_party", "drawing_customer",
            "letter_recipient", "vcard_org", "calendar_summary", "certified_company",
            "screenshot_row", "certification_body", "training_provider", "filename", "email_domain");

    private final FilesDao filesDao = new FilesDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final FactsDao factsDao = new FactsDao();
    private final EntitiesDao entitiesDao = new EntitiesDao();
    private final AliasesDao aliasesDao = new AliasesDao();
    private final IssuesDao issuesDao = new IssuesDao();

    private final Connection conn;
    private final Adjudicator adjudicator;
    /** Every mention, held in memory while resolving and written back at the end. */
    private final Map<Long, MentionRow> mentions = new LinkedHashMap<>();
    private final Map<Long, FileRow> files = new HashMap<>();
    private final Map<String, Integer> stats = new TreeMap<>();
    private final Set<Long> anchors = new HashSet<>();   // owner + folder customers: preferred on ties
    private long owner;

    public Resolver(Connection conn, Adjudicator adjudicator) throws Exception {
        this.conn = conn;
        this.adjudicator = adjudicator;
        for (MentionRow m : mentionsDao.findAll(conn)) {
            mentions.put(m.id, m);
        }
        for (FileRow f : filesDao.findAll(conn)) {
            files.put(f.id, f);
        }
    }

    public static Map<String, Integer> run(Connection conn) throws Exception {
        return new Resolver(conn, ClaudeAdjudicator.createDefault()).run();
    }

    public Map<String, Integer> run() throws Exception {
        companies();
        projects();
        people();
        documents();
        products();
        flush();
        return stats;
    }

    // ================================================================ helpers

    /** Finds or creates an entity; new attrs are merged into existing ones. */
    private long entity(EntityType etype, String key, String name, Map<String, Object> attrs) throws Exception {
        EntityRow row = entitiesDao.findByTypeAndKey(conn, etype, key);
        if (row != null) {
            if (attrs != null && !attrs.isEmpty()) {
                Map<String, Object> merged = row.attrs;
                for (Map.Entry<String, Object> e : attrs.entrySet()) {
                    if (e.getValue() != null) {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
                entitiesDao.updateAttrs(conn, row.id, merged);
            }
            return row.id;
        }
        Map<String, Object> filteredAttributes = new LinkedHashMap<>();
        if (attrs != null) {
            for (Map.Entry<String, Object> e : attrs.entrySet()) {
                if (e.getValue() != null) {
                    filteredAttributes.put(e.getKey(), e.getValue());
                }
            }
        }
        return entitiesDao.insert(conn, etype, name, key, filteredAttributes);
    }

    private Map<String, Object> entityAttrs(long id) throws Exception {
        return entitiesDao.findById(conn, id).attrs;
    }

    private void saveEntityAttrs(long id, Map<String, Object> attrs) throws Exception {
        entitiesDao.updateAttrs(conn, id, attrs);
    }

    private void assign(MentionRow m, Long entityId, String method, double confidence) {
        m.entityId = entityId;
        m.method = method;
        m.confidence = confidence;
        String k = m.etype.value() + ":" + method;
        stats.put(k, stats.containsKey(k) ? stats.get(k) + 1 : 1);
    }

    private void issue(String kind, String severity, String detail, Long fileId, Long entityId) throws Exception {
        issuesDao.insert(conn, kind, severity, detail, fileId, entityId);
    }

    private List<MentionRow> filterByType(EntityType etype) {
        List<MentionRow> filteredMentions = new ArrayList<>();
        for (MentionRow m : mentions.values()) {
            if (m.etype == etype) {
                filteredMentions.add(m);
            }
        }
        return filteredMentions;
    }

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Long entityOfMention(Long mentionId) {
        MentionRow m = mentionId == null ? null : mentions.get(mentionId);
        return m == null ? null : m.entityId;
    }

    // ================================================================ companies

    private void companies() throws Exception {
        Map<Long, String> entities = new LinkedHashMap<>();   // id -> canonical name
        
        owner = entity(EntityType.COMPANY, NameMatcher.companyKey(Config.ownerName), Config.ownerName,
                attrs("role", "owner", "domain", Config.ownerDomain));
        
        entities.put(owner, Config.ownerName);
        anchors.add(owner);
        
        // Most trustworthy first (folder, bill_to, ... email_domain), so a good spelling creates each company
        // before weaker ones are matched against it. The sort is stable: same-role mentions keep their order.
        List<MentionRow> companyMentions = filterByType(EntityType.COMPANY);
        companyMentions.sort(Comparator.comparingInt(a -> roleRank(a.role)));

        // Customer folder names are the anchors.
        for (MentionRow mention : companyMentions) {
            if (mention.role.equals("folder")) {
                long id = entity(EntityType.COMPANY, NameMatcher.companyKey(mention.surface), mention.surface, attrs("role", "customer"));
                entities.put(id, mention.surface);
                anchors.add(id);
            }
        }
        
        Map<String, Object[]> memo = new HashMap<>();

        for (MentionRow m : companyMentions) {
            boolean isDomain = m.role.equals("email_domain");
            if (isDomain && NameMatcher.GENERIC_DOMAINS.contains(m.surface.toLowerCase())) {
                assign(m, null, "generic_domain", 0);
                continue;
            }
            boolean truncated = Boolean.TRUE.equals(m.attrs.get("truncated"));
            String memoKey = m.surface + "|" + isDomain + "|" + truncated;
            if (!memo.containsKey(memoKey)) {
                memo.put(memoKey, resolveCompany(m, isDomain, truncated, entities));
            }
            Object[] r = memo.get(memoKey);
            assign(m, (Long) r[0], (String) r[1], (Double) r[2] * m.confidence);
        }
    }

    private static int roleRank(String role) {
        int i = COMPANY_ROLE_ORDER.indexOf(role);
        return i < 0 ? 99 : i;
    }

    /** Returns {entity id, method, score}. */
    private Object[] resolveCompany(MentionRow m, boolean isDomain, boolean truncated, Map<Long, String> entities) throws Exception {
        final List<Object[]> scored = new ArrayList<Object[]>();   // {score, method, entity id}
        for (Map.Entry<Long, String> e : entities.entrySet()) {
            Match match = isDomain ? NameMatcher.matchDomain(m.surface, e.getValue())
                    : NameMatcher.matchCompany(m.surface, e.getValue(), truncated);
            if (match != null) {
                scored.add(new Object[] {match.score, match.method, e.getKey()});
            }
        }
        // best score first; on a tie prefer the anchors (folder customers, owner)
        Collections.sort(scored, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                int c = Double.compare((Double) b[0], (Double) a[0]);
                if (c != 0) {
                    return c;
                }
                return Boolean.compare(anchors.contains((Long) b[2]), anchors.contains((Long) a[2]));
            }
        });
        if (!scored.isEmpty() && (Double) scored.get(0)[0] >= ACCEPT) {
            Object[] best = scored.get(0);
            if (scored.size() > 1 && (Double) best[0] - (Double) scored.get(1)[0] < AMBIGUOUS_MARGIN && !"normalized".equals(best[1])) {
                issue("ambiguous_match", "warn", "company '" + m.surface + "' matches both '" + entities.get(best[2])
                        + "' and '" + entities.get(scored.get(1)[2]) + "'", m.fileId, null);
            }
            return new Object[] {best[2], best[1], best[0]};
        }
        if (!scored.isEmpty() && (Double) scored.get(0)[0] >= GRAY) {
            Object[] best = scored.get(0);
            String candidate = entities.get(best[2]);
            Adjudicator.Verdict v = adjudicator.sameEntity(EntityType.COMPANY, m.surface, candidate,
                    "seen as " + m.role + " in file " + files.get(m.fileId).path);
            if (v.same) {
                return new Object[] {best[2], "adjudicated:" + best[1], Math.min((Double) best[0], v.confidence)};
            }
            issue("possible_alias", "info", String.format("'%s' may be '%s' (%s, %.2f); kept separate: %s",
                    m.surface, candidate, best[1], (Double) best[0], v.reason), m.fileId, (Long) best[2]);
        }
        // New organisation (supplier, certification body, unknown customer, ...)
        String name = isDomain ? m.surface.toLowerCase() : m.surface;
        String key = isDomain ? "domain:" + name : NameMatcher.companyKey(name);
        long id = entity(EntityType.COMPANY, key, name, isDomain ? attrs("domain", name) : null);
        entities.put(id, name);
        return new Object[] {id, "new", 1.0};
    }

    // ================================================================ projects

    private void projects() throws Exception {
        Map<String, List<Long>> byTitle = new LinkedHashMap<String, List<Long>>();   // normalised title -> projects
        Map<Long, Long> companyOfProject = new HashMap<Long, Long>();

        // 1. explicit job ids (folders first so their titles and customers win)
        List<MentionRow> withId = new ArrayList<MentionRow>();
        for (MentionRow m : filterByType(EntityType.PROJECT)) {
            if (m.attrs.get("job_id") != null) {
                withId.add(m);
            }
        }
        Collections.sort(withId, new Comparator<MentionRow>() {
            public int compare(MentionRow a, MentionRow b) {
                return Boolean.compare(!a.role.equals("folder"), !b.role.equals("folder"));
            }
        });
        for (MentionRow m : withId) {
            String jobId = m.attrString("job_id");
            Long company = entityOfMention(m.attrId("company_mention"));
            String title = m.surface.equals(jobId) ? null : m.surface;
            Map<String, Object> a = attrs("job_id", jobId, "title", title, "company_id", company,
                    "source", m.role.equals("folder") ? "folder" : m.role,
                    "status", m.attrs.get("status"), "value", m.attrs.get("value"));
            EntityRow existing = entitiesDao.findByTypeAndKey(conn, EntityType.PROJECT, jobId);
            if (existing != null) {   // never overwrite what the folder said, except live status/value
                Map<String, Object> old = existing.attrs;
                Map<String, Object> keep = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> e : a.entrySet()) {
                    if (!old.containsKey(e.getKey()) || e.getKey().equals("status") || e.getKey().equals("value")) {
                        keep.put(e.getKey(), e.getValue());
                    }
                }
                a = keep;
            }
            long id = entity(EntityType.PROJECT, jobId, title != null ? jobId + " " + title : jobId, a);
            if (m.role.equals("folder") && title != null) {
                String tk = titleKey(title);
                if (!byTitle.containsKey(tk)) {
                    byTitle.put(tk, new ArrayList<Long>());
                }
                if (!byTitle.get(tk).contains(id)) {
                    byTitle.get(tk).add(id);
                }
                companyOfProject.put(id, company);
            }
            assign(m, id, "job_id", m.role.equals("folder") ? 1.0 : 0.95);
        }
        for (List<Long> ids : byTitle.values()) {
            Collections.sort(ids);
        }

        // 2. title-only mentions ('Job: Palletiser Line Upgrade') need the customer as context
        Map<Long, Long> folderProject = new HashMap<Long, Long>();
        for (MentionRow m : filterByType(EntityType.PROJECT)) {
            if (m.role.equals("folder")) {
                folderProject.put(m.fileId, m.entityId);
            }
        }
        for (MentionRow m : filterByType(EntityType.PROJECT)) {
            if (m.entityId != null) {
                continue;
            }
            String tk = titleKey(m.surface);
            List<Long> cands = byTitle.containsKey(tk) ? byTitle.get(tk) : fuzzyTitles(tk, byTitle);
            Long company = entityOfMention(m.attrId("company_mention"));
            Long here = folderProject.get(m.fileId);
            Long chosen = null;
            String method = null;
            if (company != null) {
                List<Long> sameCompany = new ArrayList<Long>();
                for (Long c : cands) {
                    if (company.equals(companyOfProject.get(c))) {
                        sameCompany.add(c);
                    }
                }
                if (sameCompany.size() == 1) {
                    chosen = sameCompany.get(0);
                    method = "title+company";
                } else if (sameCompany.size() > 1 && sameCompany.contains(here)) {
                    chosen = here;
                    method = "title+company+folder";
                }
            }
            if (chosen == null && here != null && cands.contains(here)) {
                chosen = here;
                method = "title+folder";
            }
            if (chosen == null && cands.size() == 1) {
                chosen = cands.get(0);
                method = "unique_title";
            }
            if (chosen == null) {
                assign(m, null, "unresolved", 0);
                if (!cands.isEmpty()) {
                    issue("ambiguous_project", "info", "job title '" + m.surface + "' matches " + cands.size()
                            + " projects; no company context", m.fileId, null);
                }
                continue;
            }
            assign(m, chosen, method, method.contains("company") ? 0.9 : 0.75);
            if (here != null && !chosen.equals(here)) {
                issue("misfiled", "warn", "document is filed under " + files.get(m.fileId).folderJob
                        + " but refers to '" + m.surface + "' of another project", m.fileId, chosen);
            }
        }
    }

    private static String titleKey(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Candidates of the most similar known title, when it is at least 90% similar. */
    private static List<Long> fuzzyTitles(String tk, Map<String, List<Long>> byTitle) {
        String bestKey = null;
        double bestScore = 0;
        for (String k : byTitle.keySet()) {
            double r = Text.ratio(tk, k);
            if (r >= 90 && (r > bestScore || (r == bestScore && k.compareTo(bestKey) > 0))) {
                bestScore = r;
                bestKey = k;
            }
        }
        return bestKey == null ? new ArrayList<Long>() : byTitle.get(bestKey);
    }

    // ================================================================ people

    private void people() throws Exception {
        Map<Long, Long> folderCompany = new HashMap<Long, Long>();
        for (MentionRow m : filterByType(EntityType.COMPANY)) {
            if (m.role.equals("folder")) {
                folderCompany.put(m.fileId, m.entityId);
            }
        }
        Map<String, Long> clusters = new LinkedHashMap<String, Long>();   // "name key|org" -> person
        Map<String, Long> byEmail = new HashMap<String, Long>();
        List<MentionRow> persons = filterByType(EntityType.PERSON);

        // 1. people with a known organisation (email domain, bill-to, vCard, staff roles); e-mails first
        List<MentionRow> ordered = new ArrayList<MentionRow>(persons);
        Collections.sort(ordered, new Comparator<MentionRow>() {
            public int compare(MentionRow a, MentionRow b) {
                return Boolean.compare(a.attrs.get("email") == null, b.attrs.get("email") == null);
            }
        });
        for (MentionRow m : ordered) {
            Long org = entityOfMention(m.attrId("org_mention"));
            if (org == null) {
                continue;
            }
            String nk = NameMatcher.personKey(m.surface);
            String email = m.attrString("email");
            Long id = email != null ? byEmail.get(email) : null;
            if (id == null) {
                id = clusters.get(nk + "|" + org);
            }
            String method = email != null && byEmail.containsKey(email) ? "email" : "name+organisation";
            if (id == null) {
                id = entity(EntityType.PERSON, nk + "|" + org, m.surface, attrs("company_id", org));
                method = "new";
            }
            clusters.put(nk + "|" + org, id);
            if (email != null) {
                byEmail.put(email, id);
                addToList(id, "emails", email);
            }
            if (m.attrs.get("job_title") != null) {
                addToList(id, "job_titles", m.attrs.get("job_title"));
            }
            if (m.attrs.get("phone") != null) {
                addToList(id, "phones", m.attrs.get("phone"));
            }
            assign(m, id, method, method.equals("email") || method.equals("new") ? 1.0 : 0.95);
        }

        // name key -> {org, person} pairs
        Map<String, Set<List<Long>>> byName = new HashMap<String, Set<List<Long>>>();
        for (Map.Entry<String, Long> e : clusters.entrySet()) {
            int bar = e.getKey().lastIndexOf('|');
            addCandidate(byName, e.getKey().substring(0, bar), Long.valueOf(e.getKey().substring(bar + 1)), e.getValue());
        }
        // 2. people seen without an organisation (meeting attendees, un-attributed signatures)
        for (MentionRow m : persons) {
            if (m.entityId != null) {
                continue;
            }
            String nk = NameMatcher.personKey(m.surface);
            Set<List<Long>> cands = byName.containsKey(nk) ? byName.get(nk) : new LinkedHashSet<List<Long>>();
            String[] initial = NameMatcher.personInitialForm(m.surface);
            if (cands.isEmpty() && initial != null) {   // 'R. Bianchi'
                for (Map.Entry<String, Set<List<Long>>> e : byName.entrySet()) {
                    String[] words = e.getKey().split(" ");
                    if (words[words.length - 1].equals(initial[1]) && e.getKey().startsWith(initial[0])) {
                        cands.addAll(e.getValue());
                    }
                }
            }
            Long context = folderCompany.get(m.fileId);
            List<List<Long>> pick = new ArrayList<List<Long>>();
            for (List<Long> c : cands) {
                if (c.get(0) != null && c.get(0).equals(context)) {
                    pick.add(c);
                }
            }
            if (pick.isEmpty()) {
                for (List<Long> c : cands) {
                    if (c.get(0) != null && c.get(0) == owner) {
                        pick.add(c);
                    }
                }
            }
            long id;
            String method;
            double confidence;
            if (cands.size() == 1) {
                id = cands.iterator().next().get(1);
                method = "unique_name";
                confidence = 0.8;
            } else if (pick.size() == 1) {
                id = pick.get(0).get(1);
                method = "name+context";
                confidence = 0.7;
            } else {
                if (cands.size() > 1) {
                    issue("ambiguous_person", "info", "'" + m.surface + "' matches " + cands.size()
                            + " people at different organisations", m.fileId, null);
                }
                id = entity(EntityType.PERSON, nk + "|?", m.surface, new HashMap<String, Object>());
                method = "new_unattributed";
                confidence = 1.0;
                addCandidate(byName, nk, null, id);
            }
            assign(m, id, method, confidence);
        }
    }

    private static void addCandidate(Map<String, Set<List<Long>>> byName, String nameKey, Long org, long person) {
        if (!byName.containsKey(nameKey)) {
            byName.put(nameKey, new LinkedHashSet<List<Long>>());
        }
        byName.get(nameKey).add(java.util.Arrays.asList(org, person));
    }

    @SuppressWarnings("unchecked")
    private void addToList(long id, String key, Object value) throws Exception {
        Map<String, Object> a = entityAttrs(id);
        List<Object> list = (List<Object>) a.get(key);
        if (list == null) {
            list = new ArrayList<Object>();
            a.put(key, list);
        }
        if (!list.contains(value)) {
            list.add(value);
            saveEntityAttrs(id, a);
        }
    }

    // ================================================================ documents

    /**
     * A document number identifies a document within a counterparty: the dataset reuses some
     * numbers (DWG-9296 is two different drawings for two customers), so copies of one number
     * that disagree on customer/product are split apart.
     */
    private void documents() throws Exception {
        Map<Long, Long> party = new HashMap<Long, Long>();   // self-mention -> counterparty entity
        for (FactRow f : factsDao.findWithRelations(conn, COUNTERPARTY_RELATIONS)) {
            boolean partyTo = f.rel == RelationType.PARTY_TO;
            long docMention = partyTo ? f.dst : f.src;
            Long other = entityOfMention(partyTo ? f.src : f.dst);
            if (other != null && other != owner && !party.containsKey(docMention)) {
                party.put(docMention, other);
            }
        }
        Map<Long, Long> folderCompany = new HashMap<Long, Long>();
        for (MentionRow m : filterByType(EntityType.COMPANY)) {
            if (m.role.equals("folder")) {
                folderCompany.put(m.fileId, m.entityId);
            }
        }

        List<MentionRow> docs = filterByType(EntityType.DOCUMENT);
        Map<String, Set<Long>> partiesOfKey = new LinkedHashMap<String, Set<Long>>();
        for (MentionRow m : docs) {
            if (m.role.equals("self")) {
                String key = docKey(m);
                if (!partiesOfKey.containsKey(key)) {
                    partiesOfKey.put(key, new LinkedHashSet<Long>());
                }
                if (party.get(m.id) != null) {
                    partiesOfKey.get(key).add(party.get(m.id));
                }
            }
        }
        Map<String, Set<Long>> splitKeys = new HashMap<String, Set<Long>>();
        for (Map.Entry<String, Set<Long>> e : partiesOfKey.entrySet()) {
            if (e.getValue().size() > 1 && e.getKey().matches("^[A-Z]+-\\d.*")) {
                splitKeys.put(e.getKey(), e.getValue());
                TreeSet<String> names = new TreeSet<String>();
                for (Long p : e.getValue()) {
                    names.add(entitiesDao.findById(conn, p).name);
                }
                issue("number_collision", "warn", e.getKey() + " is used by " + e.getValue().size()
                        + " different documents (" + NameMatcher.join(new ArrayList<String>(names), ", ")
                        + "); kept as separate documents", null, null);
            }
        }

        // file-backed first, so referenced-only documents attach to them
        List<MentionRow> ordered = new ArrayList<MentionRow>(docs);
        Collections.sort(ordered, new Comparator<MentionRow>() {
            public int compare(MentionRow a, MentionRow b) {
                return Boolean.compare(!a.role.equals("self"), !b.role.equals("self"));
            }
        });
        for (MentionRow m : ordered) {
            String key = docKey(m);
            Long counterparty = null;
            if (splitKeys.containsKey(key)) {
                counterparty = m.role.equals("self") ? party.get(m.id) : folderCompany.get(m.fileId);
                if (counterparty == null || !splitKeys.get(key).contains(counterparty)) {
                    assign(m, null, "ambiguous_number", 0);
                    continue;
                }
                key = key + "@" + counterparty;
            }
            long id = entity(EntityType.DOCUMENT, key, m.surface, null);
            if (m.role.equals("self")) {
                mergeDocAttrs(id, m);
            }
            String method = key.matches("^[A-Z]+-\\d.*") ? "doc_number" : "file_identity";
            assign(m, id, method + (counterparty != null ? "+counterparty" : ""), m.role.equals("self") ? 1.0 : 0.9);
        }
    }

    private static String docKey(MentionRow m) {
        String k = m.attrString("key");
        return k != null ? k : m.surface;
    }

    @SuppressWarnings("unchecked")
    private void mergeDocAttrs(long id, MentionRow m) throws Exception {
        Map<String, Object> a = entityAttrs(id);
        List<Object> fileIds = (List<Object>) a.get("files");
        if (fileIds == null) {
            fileIds = new ArrayList<Object>();
            a.put("files", fileIds);
        }
        if (!containsNumber(fileIds, m.fileId)) {
            fileIds.add(m.fileId);
        }
        for (Map.Entry<String, Object> e : m.attrs.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.equals("key") || k.equals("version") || v == null) {
                continue;
            }
            boolean trackConflicts = k.equals("total") || k.equals("date") || k.equals("job_title");
            if (a.containsKey(k) && !a.get(k).equals(v) && trackConflicts) {
                Map<String, Object> conflicts = (Map<String, Object>) a.get("conflicts");
                if (conflicts == null) {
                    conflicts = new LinkedHashMap<String, Object>();
                    a.put("conflicts", conflicts);
                }
                List<Object> values = (List<Object>) conflicts.get(k);
                if (values == null) {
                    values = new ArrayList<Object>();
                    values.add(a.get(k));
                    conflicts.put(k, values);
                }
                if (!values.contains(v)) {
                    values.add(v);
                }
            } else if (!a.containsKey(k) || (k.equals("doc_type") && "other".equals(a.get(k)))) {
                a.put(k, v);
            }
        }
        if (m.attrs.get("version") != null) {
            List<Object> versions = (List<Object>) a.get("versions");
            if (versions == null) {
                versions = new ArrayList<Object>();
                a.put("versions", versions);
            }
            versions.add(m.attrs.get("version"));
        }
        saveEntityAttrs(id, a);
    }

    private static boolean containsNumber(List<Object> list, long value) {
        for (Object o : list) {
            if (((Number) o).longValue() == value) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ products

    private void products() throws Exception {
        Map<Long, Map<String, Integer>> descriptions = new LinkedHashMap<Long, Map<String, Integer>>();
        for (MentionRow m : filterByType(EntityType.PRODUCT)) {
            String code = m.attrString("code") != null ? m.attrString("code") : m.surface;
            long id = entity(EntityType.PRODUCT, code, code, attrs("code", code));
            if (!m.surface.equals(code)) {
                if (!descriptions.containsKey(id)) {
                    descriptions.put(id, new LinkedHashMap<String, Integer>());
                }
                Map<String, Integer> c = descriptions.get(id);
                c.put(m.surface, c.containsKey(m.surface) ? c.get(m.surface) + 1 : 1);
            }
            assign(m, id, "product_code", m.confidence);
        }
        // name each product after its most common line-item description
        for (Map.Entry<Long, Map<String, Integer>> e : descriptions.entrySet()) {
            String best = null;
            for (Map.Entry<String, Integer> d : e.getValue().entrySet()) {
                if (best == null || d.getValue() > e.getValue().get(best)) {
                    best = d.getKey();
                }
            }
            entitiesDao.updateName(conn, e.getKey(), best);
        }
    }

    // ================================================================ write-back

    private void flush() throws Exception {
        for (MentionRow m : mentions.values()) {
            mentionsDao.updateResolution(conn, m);
        }
        aliasesDao.rebuild(conn);
        Db.commit(conn);
    }
}
