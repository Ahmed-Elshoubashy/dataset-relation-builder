package com.dubsof.graph.resolve;

import com.dubsof.graph.dao.AliasesDao;
import com.dubsof.graph.dao.EntitiesDao;
import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.row.EntityRow;
import com.dubsof.graph.dao.row.FactRow;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dao.row.MentionRow;
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.resolve.NameMatcher.Match;
import com.dubsof.graph.util.Text;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stage 4: decide which real-world thing each mention refers to.
 *
 * Extract wrote mentions ("ACME Corp" as bill_to in file 206, "Acme Corporation" as folder, ...) without
 * deciding anything. This class groups them into entities and gives every mention:
 * <ul>
 *   <li>{@code entity_id}: the entity it refers to (null when it cannot be decided),</li>
 *   <li>{@code method}: the rule that decided it (normalized, acronym, job_id, email, ...),</li>
 *   <li>{@code confidence}: how sure that rule is.</li>
 * </ul>
 *
 * The order of the steps matters. Companies come first because the other types use them as context:
 * a project title is matched within a customer, a person's name within an organisation, and a reused
 * document number is split by customer.
 *
 * All mentions are loaded into memory at the start, changed in memory, and written back once at the end.
 * Entities are written to the database as soon as they are created, because later steps look them up.
 */
public class Resolver {

    /** A company match at or above this score is accepted automatically. */
    static final double ACCEPT = 0.80;
    /** Between GRAY and ACCEPT, the Adjudicator decides; below GRAY, the name is a new company. */
    static final double GRAY = 0.65;

    /** Facts whose other end names the customer (or product) a document belongs to. */
    private static final List<RelationType> COUNTERPARTY_RELATIONS = Arrays.asList(
            RelationType.ISSUED_TO, RelationType.ADDRESSED_TO, RelationType.PARTY_TO, RelationType.DESCRIBES);

    /** A document key that is a real document number (INV-8034, DWG-9296, ...), not a file identity. */
    private static final String DOCUMENT_NUMBER = "^[A-Z]+-\\d.*";

    private final FilesDao filesDao = new FilesDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final FactsDao factsDao = new FactsDao();
    private final EntitiesDao entitiesDao = new EntitiesDao();
    private final AliasesDao aliasesDao = new AliasesDao();

    private final Connection conn;
    private final Adjudicator adjudicator;
    private final Dataset dataset;
    private final NameMatcher names;

    /** Every mention by id. Resolving changes these rows in memory; saveResolutions() writes them back. */
    private final Map<Long, MentionRow> mentionsById = new LinkedHashMap<>();
    private final Map<Long, FileRow> filesById = new HashMap<>();
    /** "etype:method" -> number of mentions resolved that way (the stage's summary). */
    private final Map<String, Integer> methodCounts = new TreeMap<>();
    /** The owner and the customer-folder companies: certain, so they win ties when matching. */
    private final Set<Long> anchorCompanyIds = new HashSet<>();
    /** Companies known only by an e-mail domain ("kestrelfoods.com"): no name matched it when it was seen. */
    private final Set<Long> domainCompanyIds = new HashSet<>();
    /** The owner company's entity id; 0 (no entity has it) when the dataset has no known owner. */
    private long ownerCompanyId;

    /** One possible company for a mention: which company, how well it matched and by which rule. */
    private static class CompanyCandidate {
        final long companyId;
        final double score;
        final String method;

        CompanyCandidate(long companyId, double score, String method) {
            this.companyId = companyId;
            this.score = score;
            this.method = method;
        }
    }

    /** The decision for one company spelling: the company, the rule, and the score. */
    private static class CompanyDecision {
        final long companyId;
        final String method;
        final double score;

        CompanyDecision(long companyId, String method, double score) {
            this.companyId = companyId;
            this.method = method;
            this.score = score;
        }
    }

    /** A person already known under a name: their organisation (null when unknown) and their entity id. */
    private static class KnownPerson {
        final Long orgId;
        final long personId;

        KnownPerson(Long orgId, long personId) {
            this.orgId = orgId;
            this.personId = personId;
        }

        // needed because KnownPerson is kept in sets: the same (org, person) pair must be counted once
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof KnownPerson)) {
                return false;
            }
            KnownPerson that = (KnownPerson) other;
            return Objects.equals(orgId, that.orgId) && personId == that.personId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, personId);
        }
    }

    public Resolver(Connection conn, Adjudicator adjudicator, Dataset dataset) throws Exception {
        this.conn = conn;
        this.adjudicator = adjudicator;
        this.dataset = dataset;
        this.names = dataset.names;
        for (MentionRow mention : mentionsDao.findAll(conn)) {
            mentionsById.put(mention.id, mention);
        }
        for (FileRow file : filesDao.findAll(conn)) {
            filesById.put(file.id, file);
        }
    }

    public static Map<String, Integer> run(Connection conn, Dataset dataset) throws Exception {
        return new Resolver(conn, ClaudeAdjudicator.createDefault(dataset.owner), dataset).run();
    }

    public Map<String, Integer> run() throws Exception {
        resolveCompanies();
        resolveProjects();
        resolvePeople();
        resolveDocuments();
        resolveProducts();
        saveResolutions();
        return methodCounts;
    }

    // ================================================================ companies

    /**
     * Gives every company mention its company. The owner and the customer folders are created first
     * as anchors; then every mention, most trustworthy role first, either matches a known company
     * or becomes a new one that later mentions can match.
     */
    private void resolveCompanies() throws Exception {
        // id -> name of every company known so far; each mention is compared against all of them
        Map<Long, String> knownCompanies = new LinkedHashMap<>();

        // 1. The owner of the file share (detected from letterheads and e-mail senders), if there is one.
        Owner owner = dataset.owner;
        if (owner.isKnown()) {
            ownerCompanyId = findOrCreateEntity(EntityType.COMPANY, names.companyKey(owner.name), owner.name,
                    attributes("role", "owner", "domain", owner.domain));
            knownCompanies.put(ownerCompanyId, owner.name);
            anchorCompanyIds.add(ownerCompanyId);
        }

        // Most trustworthy role first (folder, bill_to, ..., filename, email_domain), so a good spelling
        // creates each company before weaker ones are matched against it. The sort is stable: mentions
        // with the same role keep their order.
        List<MentionRow> companyMentions = mentionsOfType(EntityType.COMPANY);
        companyMentions.sort(Comparator.comparingInt(mention -> mention.role.companyRank()));

        // 2. Customer folder names (from the profile's folder layout): typed by a person, so they are always right.
        for (MentionRow mention : companyMentions) {
            if (mention.role == MentionRole.FOLDER) {
                long companyId = findOrCreateEntity(EntityType.COMPANY, names.companyKey(mention.surface), mention.surface,
                        attributes("role", "customer"));
                knownCompanies.put(companyId, mention.surface);
                anchorCompanyIds.add(companyId);
            }
        }

        // 3. Every company mention, including the folder ones (they simply match their own anchor).
        //    The same spelling appears in many files, so each spelling is decided once and reused.
        Map<String, CompanyDecision> decisionBySpelling = new HashMap<>();
        for (MentionRow mention : companyMentions) {
            boolean isDomain = mention.role == MentionRole.EMAIL_DOMAIN;

            // gmail.com, outlook.com, ...: a free e-mail provider says nothing about someone's employer
            if (isDomain && names.isGenericDomain(mention.surface)) {
                assignEntity(mention, null, "generic_domain", 0);
                continue;
            }

            // a name cut off in a filename ("Redwood Timber & J") is matched more loosely
            boolean truncated = Boolean.TRUE.equals(mention.attrs.get("truncated"));
            String spelling = mention.surface + "|" + isDomain + "|" + truncated;

            CompanyDecision decision = decisionBySpelling.get(spelling);
            if (decision == null) {
                decision = decideCompany(mention, isDomain, truncated, knownCompanies);
                decisionBySpelling.put(spelling, decision);
            }
            // a weak source (e.g. filename, 0.7) lowers the confidence of even a perfect name match
            assignEntity(mention, decision.companyId, decision.method, decision.score * mention.confidence);
        }

        // 4. Step 3 is greedy: the first spelling seen names a new company, even a typo ("Kestral Foods Inc"),
        //    and a domain or a longer name seen before its company's right spelling could not join it.
        //    Now that every spelling is known, fix both: rename, merge what matches, and rename the merged ones.
        nameCompaniesByBestSpelling(knownCompanies);
        mergeSameCompanies(knownCompanies);
        nameCompaniesByBestSpelling(knownCompanies);
    }

    /**
     * Renames every company that is not an anchor after the spelling its other spellings agree with most,
     * so a typo seen first ("Kestral Foods Inc") does not name the company. Each spelling is scored by how
     * well every mention of the company matches it (see {@link NameMatcher#supportsName}); counting exact
     * spellings is not enough when a name is written many ways ("Witmore Dairy" can be the single most
     * common one). Filenames (often cut off) and e-mail domains never name a company. The key stays as it was.
     */
    private void nameCompaniesByBestSpelling(Map<Long, String> knownCompanies) throws Exception {
        for (Map.Entry<Long, List<MentionRow>> company : mentionsByCompany().entrySet()) {
            long companyId = company.getKey();
            if (anchorCompanyIds.contains(companyId)) {
                continue;
            }
            // spelling -> number of mentions, in the order first seen (ties keep the first)
            Map<String, Integer> spellings = new LinkedHashMap<>();
            for (MentionRow mention : company.getValue()) {
                if (mention.role != MentionRole.EMAIL_DOMAIN && mention.role != MentionRole.FILENAME) {
                    spellings.merge(mention.surface, 1, Integer::sum);
                }
            }
            String best = null;
            double bestSupport = 0;
            for (String name : spellings.keySet()) {
                double support = 0;
                for (Map.Entry<String, Integer> spelling : spellings.entrySet()) {
                    support += spelling.getValue() * names.supportsName(spelling.getKey(), name);
                }
                // spellings that differ only in punctuation or legal suffix tie: the more frequent one wins
                boolean tie = Math.abs(support - bestSupport) < 1e-9;
                if ((support > bestSupport && !tie) || (tie && best != null && spellings.get(name) > spellings.get(best))) {
                    best = name;
                    bestSupport = support;
                }
            }
            if (best != null && !best.equals(knownCompanies.get(companyId))) {
                entitiesDao.updateName(conn, companyId, best);
                knownCompanies.put(companyId, best);
            }
        }
    }

    /**
     * Merges companies that step 3 kept apart only because of the order it saw them in: "Bayview Dental"
     * was created before "Bayview Dental Supplies Inc" came along, and kestrelfoods.com before its company
     * had the right name. Every company that is not an anchor is compared with every other one, both ways
     * and domains with names, and a pair scoring ACCEPT or more becomes one company. The anchor is kept,
     * else the named company over a domain, else the one with more mentions. Borderline pairs (GRAY..ACCEPT)
     * stay apart. This is O(n²) in companies, which number in the tens or hundreds, so it is cheap.
     */
    private void mergeSameCompanies(Map<Long, String> knownCompanies) throws Exception {
        Map<Long, List<MentionRow>> mentionsByCompany = mentionsByCompany();
        boolean mergedOne = true;
        while (mergedOne) {
            mergedOne = false;
            for (Long companyId : new ArrayList<>(knownCompanies.keySet())) {
                if (anchorCompanyIds.contains(companyId)) {
                    continue;
                }
                for (Long otherId : new ArrayList<>(knownCompanies.keySet())) {
                    Match match = otherId.equals(companyId) ? null : sameCompany(companyId, otherId, knownCompanies);
                    if (match == null || match.score < ACCEPT) {
                        continue;
                    }
                    long kept = keptCompany(companyId, otherId, mentionsByCompany);
                    long gone = kept == companyId ? otherId : companyId;
                    mergeCompany(gone, kept, match, mentionsByCompany, knownCompanies);
                    mergedOne = true;
                    break;
                }
                if (mergedOne) {
                    break;   // the company list changed: start again
                }
            }
        }
    }

    /** How well two companies match, trying both directions (a longer name only matches one way); null: not at all. */
    private Match sameCompany(long companyId, long otherId, Map<Long, String> knownCompanies) {
        String name = knownCompanies.get(companyId);
        String otherName = knownCompanies.get(otherId);
        boolean isDomain = domainCompanyIds.contains(companyId);
        boolean otherIsDomain = domainCompanyIds.contains(otherId);
        if (isDomain && otherIsDomain) {
            return null;   // two different domains can belong to one company, but nothing here says so
        }
        if (isDomain || otherIsDomain) {
            return isDomain ? names.matchDomain(name, otherName) : names.matchDomain(otherName, name);
        }
        Match oneWay = names.matchCompany(name, otherName, false);
        Match otherWay = names.matchCompany(otherName, name, false);
        if (oneWay == null || (otherWay != null && otherWay.score > oneWay.score)) {
            return otherWay;
        }
        return oneWay;
    }

    /** Of two companies that are the same, the one to keep: an anchor, else a named one over a domain, else the one with more mentions. */
    private long keptCompany(long companyId, long otherId, Map<Long, List<MentionRow>> mentionsByCompany) {
        if (anchorCompanyIds.contains(otherId)) {
            return otherId;
        }
        if (domainCompanyIds.contains(companyId) != domainCompanyIds.contains(otherId)) {
            return domainCompanyIds.contains(companyId) ? otherId : companyId;
        }
        int mentions = mentionsByCompany.getOrDefault(companyId, new ArrayList<>()).size();
        int otherMentions = mentionsByCompany.getOrDefault(otherId, new ArrayList<>()).size();
        if (mentions != otherMentions) {
            return mentions > otherMentions ? companyId : otherId;
        }
        return Math.min(companyId, otherId);
    }

    /** Moves the mentions of {@code gone} to {@code kept} (method "merged:rule") and deletes {@code gone}. */
    private void mergeCompany(long gone, long kept, Match match, Map<Long, List<MentionRow>> mentionsByCompany,
                              Map<Long, String> knownCompanies) throws Exception {
        List<MentionRow> moved = mentionsByCompany.containsKey(gone) ? mentionsByCompany.remove(gone) : new ArrayList<>();
        for (MentionRow mention : moved) {
            countMethod(mention.etype, mention.method, -1);
            assignEntity(mention, kept, "merged:" + match.methodName(), Math.min(mention.confidence, match.score));
        }
        mentionsByCompany.computeIfAbsent(kept, id -> new ArrayList<>()).addAll(moved);

        // a merged domain tells the company's domain
        EntityRow keptCompany = entitiesDao.findById(conn, kept);
        if (domainCompanyIds.contains(gone) && keptCompany.attrs.get("domain") == null) {
            keptCompany.attrs.put("domain", knownCompanies.get(gone));
            entitiesDao.updateAttrs(conn, kept, keptCompany.attrs);
        }
        entitiesDao.delete(conn, gone);
        knownCompanies.remove(gone);
        domainCompanyIds.remove(gone);
    }

    /** Company id -> its mentions, in id order. */
    private Map<Long, List<MentionRow>> mentionsByCompany() {
        Map<Long, List<MentionRow>> byCompany = new LinkedHashMap<>();
        for (MentionRow mention : mentionsOfType(EntityType.COMPANY)) {
            if (mention.entityId != null) {
                byCompany.computeIfAbsent(mention.entityId, id -> new ArrayList<>()).add(mention);
            }
        }
        return byCompany;
    }

    /**
     * Finds the company a spelling refers to, or creates a new company for it.
     * Scores the spelling against every known company and decides by the best score:
     * at or above ACCEPT it is the same company, between GRAY and ACCEPT the Adjudicator decides,
     * otherwise it is a new company.
     */
    private CompanyDecision decideCompany(MentionRow mention, boolean isDomain, boolean truncated, Map<Long, String> knownCompanies) throws Exception {
        
        List<CompanyCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Long, String> known : knownCompanies.entrySet()) {
            Match match = isDomain ? names.matchDomain(mention.surface, known.getValue())
                    : names.matchCompany(mention.surface, known.getValue(), truncated);
            if (match != null) {
                candidates.add(new CompanyCandidate(known.getKey(), match.score, match.methodName()));
            }
        }

        // best score first; on a tie prefer an anchor (the owner or a customer folder)
        candidates.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) {
                return byScore;
            }
            return Boolean.compare(anchorCompanyIds.contains(b.companyId), anchorCompanyIds.contains(a.companyId));
        });
        CompanyCandidate best = candidates.isEmpty() ? null : candidates.get(0);

        // Good enough: it is this company.
        if (best != null && best.score >= ACCEPT) {
            return new CompanyDecision(best.companyId, best.method, best.score);
        }

        // Borderline: ask the Adjudicator (by default it says no; with ERKG_ADJUDICATOR=claude it asks Claude).
        if (best != null && best.score >= GRAY) {
            String candidateName = knownCompanies.get(best.companyId);
            Adjudicator.Verdict verdict = adjudicator.sameEntity(EntityType.COMPANY, mention.surface, candidateName,
                    "seen as " + mention.role.value() + " in file " + filesById.get(mention.fileId).path);
            if (verdict.same) {
                return new CompanyDecision(best.companyId, "adjudicated:" + best.method, Math.min(best.score, verdict.confidence));
            }
        }

        // "Ask Harbor Robotics Inc": the free-text rules may take a sentence's first word into a name.
        // Without it, the name may be a known company.
        String withoutFirstWord = NameMatcher.withoutFirstWord(mention.surface);
        if (mention.role == MentionRole.FREE_TEXT && (best == null || best.score < GRAY) && withoutFirstWord != null) {
            CompanyCandidate shorter = null;
            for (Map.Entry<Long, String> known : knownCompanies.entrySet()) {
                Match match = names.matchCompany(withoutFirstWord, known.getValue(), false);
                if (match != null && match.score >= ACCEPT && (shorter == null || match.score > shorter.score)) {
                    shorter = new CompanyCandidate(known.getKey(), match.score, match.methodName());
                }
            }
            if (shorter != null) {
                return new CompanyDecision(shorter.companyId, "leading_word+" + shorter.method, shorter.score * 0.9);
            }
        }

        // No match: a new organisation (supplier, certification body, unknown customer, ...).
        // It is added to knownCompanies, so later mentions can match it.
        String name = isDomain ? mention.surface.toLowerCase() : mention.surface;
        String key = isDomain ? "domain:" + name : names.companyKey(name);
        long companyId = findOrCreateEntity(EntityType.COMPANY, key, name, isDomain ? attributes("domain", name) : null);
        knownCompanies.put(companyId, name);
        if (isDomain) {
            domainCompanyIds.add(companyId);
        }
        return new CompanyDecision(companyId, "new", 1.0);
    }

    // ================================================================ projects

    /**
     * Gives every project mention its project. A job id (from the profile's format) identifies a project exactly;
     * a title alone ("Job: Shrink Wrap Retrofit") is matched to a folder project using the customer as context.
     * A dataset without project folders gets its projects from titles seen in several files (step 3).
     */
    private void resolveProjects() throws Exception {
        // title key -> projects that have a folder with that title (one title can exist for two customers)
        Map<String, List<Long>> folderProjectsByTitle = new LinkedHashMap<>();
        // project id -> its customer, from the folder
        Map<Long, Long> customerOfProject = new HashMap<>();

        // Step 1: mentions that carry a JOB code. One project per code.
        // Folder mentions first, so the folder's title and customer are the ones kept.
        List<MentionRow> mentionsWithJobId = new ArrayList<>();
        for (MentionRow mention : mentionsOfType(EntityType.PROJECT)) {
            if (mention.attrs.get("job_id") != null) {
                mentionsWithJobId.add(mention);
            }
        }
        mentionsWithJobId.sort(Comparator.comparing(mention -> mention.role != MentionRole.FOLDER));

        for (MentionRow mention : mentionsWithJobId) {
            String jobId = mention.attrString("job_id");
            Long customerId = entityIdOfMention(mention.attrId("company_mention"));
            String title = mention.surface.equals(jobId) ? null : mention.surface;
            Map<String, Object> projectAttributes = attributes("job_id", jobId, "title", title, "company_id", customerId,
                    "source", mention.role.value(),
                    "status", mention.attrs.get("status"), "value", mention.attrs.get("value"));

            // The project already exists (from its folder): keep what the folder said and only add
            // attributes it does not have yet, except status and value, which later sources may update.
            EntityRow existingProject = entitiesDao.findByTypeAndKey(conn, EntityType.PROJECT, jobId);
            if (existingProject != null) {
                Map<String, Object> newAttributes = new LinkedHashMap<>();
                for (Map.Entry<String, Object> attribute : projectAttributes.entrySet()) {
                    String key = attribute.getKey();
                    if (!existingProject.attrs.containsKey(key) || key.equals("status") || key.equals("value")) {
                        newAttributes.put(key, attribute.getValue());
                    }
                }
                projectAttributes = newAttributes;
            }
            long projectId = findOrCreateEntity(EntityType.PROJECT, jobId, title != null ? jobId + " " + title : jobId,
                    projectAttributes);

            // remember folder projects by title, for step 2
            if (mention.role == MentionRole.FOLDER && title != null) {
                String titleKey = titleKey(title);
                if (!folderProjectsByTitle.containsKey(titleKey)) {
                    folderProjectsByTitle.put(titleKey, new ArrayList<>());
                }
                if (!folderProjectsByTitle.get(titleKey).contains(projectId)) {
                    folderProjectsByTitle.get(titleKey).add(projectId);
                }
                customerOfProject.put(projectId, customerId);
            }
            assignEntity(mention, projectId, "job_id", mention.role == MentionRole.FOLDER ? 1.0 : 0.95);
        }
        for (List<Long> projectIds : folderProjectsByTitle.values()) {
            projectIds.sort(null);
        }

        // Step 2: mentions with only a title. Several customers can have a project with the same title,
        // so the customer the document names, or the folder the file sits in, picks the right one.
        Map<Long, Long> folderProjectOfFile = new HashMap<>();   // file id -> project of its job folder
        for (MentionRow mention : mentionsOfType(EntityType.PROJECT)) {
            if (mention.role == MentionRole.FOLDER) {
                folderProjectOfFile.put(mention.fileId, mention.entityId);
            }
        }
        List<MentionRow> withoutProject = new ArrayList<>();
        for (MentionRow mention : mentionsOfType(EntityType.PROJECT)) {
            if (mention.entityId != null) {
                continue;   // already resolved by its JOB code in step 1
            }
            String titleKey = titleKey(mention.surface);
            List<Long> candidates = folderProjectsByTitle.containsKey(titleKey) ? folderProjectsByTitle.get(titleKey)
                    : projectsWithSimilarTitle(titleKey, folderProjectsByTitle);
            Long customerId = entityIdOfMention(mention.attrId("company_mention"));
            Long projectOfFolder = folderProjectOfFile.get(mention.fileId);

            Long chosen = null;
            String method = null;
            // a) the one candidate of the customer the document names (or, if that customer has
            //    several, the one whose folder holds this file)
            if (customerId != null) {
                List<Long> ofThisCustomer = new ArrayList<>();
                for (Long candidate : candidates) {
                    if (customerId.equals(customerOfProject.get(candidate))) {
                        ofThisCustomer.add(candidate);
                    }
                }
                if (ofThisCustomer.size() == 1) {
                    chosen = ofThisCustomer.get(0);
                    method = "title+company";
                } else if (ofThisCustomer.size() > 1 && ofThisCustomer.contains(projectOfFolder)) {
                    chosen = projectOfFolder;
                    method = "title+company+folder";
                }
            }
            // b) the candidate whose folder holds this file
            if (chosen == null && projectOfFolder != null && candidates.contains(projectOfFolder)) {
                chosen = projectOfFolder;
                method = "title+folder";
            }
            // c) the only project with this title
            if (chosen == null && candidates.size() == 1) {
                chosen = candidates.get(0);
                method = "unique_title";
            }

            if (chosen == null) {
                withoutProject.add(mention);   // no candidate, or several and no context
                continue;
            }
            assignEntity(mention, chosen, method, method.contains("company") ? 0.9 : 0.75);
        }

        // Step 3: no folder defines a project (no folder pattern, or none that names projects).
        // With project folders, a title that matches none of them stays unresolved: it is more likely
        // a typo or a project filed elsewhere than a new project.
        if (folderProjectsByTitle.isEmpty()) {
            resolveTitleOnlyProjects(withoutProject);
        } else {
            for (MentionRow mention : withoutProject) {
                assignEntity(mention, null, "unresolved", 0);
            }
        }
    }

    /**
     * Projects of a dataset without project folders, from their titles ("Job: Conveyor Upgrade"). A title seen
     * in at least 2 files for the same customer becomes a project, keyed by title and customer; a title seen
     * once stays unresolved, as it may be a one-off description. A mention without a customer joins the project
     * with its title when there is only one, or a customer-less project when its title is in 2+ files.
     * Method "title_only", confidence 0.6: a title is weaker evidence than a folder or a job id.
     */
    private void resolveTitleOnlyProjects(List<MentionRow> mentions) throws Exception {
        // "title key|customer id" ("title key|" without a customer) -> the files it appears in
        Map<String, Set<Long>> filesByTitleAndCustomer = new LinkedHashMap<>();
        for (MentionRow mention : mentions) {
            filesByTitleAndCustomer.computeIfAbsent(titleAndCustomer(mention), group -> new HashSet<>()).add(mention.fileId);
        }

        // title key -> the projects created for it, one per customer
        Map<String, List<Long>> projectsByTitle = new HashMap<>();
        Map<String, Long> projectByTitleAndCustomer = new HashMap<>();
        for (MentionRow mention : mentions) {
            Long customerId = entityIdOfMention(mention.attrId("company_mention"));
            String group = titleAndCustomer(mention);
            if (customerId == null || filesByTitleAndCustomer.get(group).size() < 2 || projectByTitleAndCustomer.containsKey(group)) {
                continue;
            }
            String customerKey = entitiesDao.findById(conn, customerId).key;
            long projectId = findOrCreateEntity(EntityType.PROJECT, "title:" + titleKey(mention.surface) + "@" + customerKey,
                    mention.surface, attributes("title", mention.surface, "company_id", customerId, "source", "title_only"));
            projectByTitleAndCustomer.put(group, projectId);
            projectsByTitle.computeIfAbsent(titleKey(mention.surface), title -> new ArrayList<>()).add(projectId);
        }

        for (MentionRow mention : mentions) {
            Long projectId = projectByTitleAndCustomer.get(titleAndCustomer(mention));
            boolean hasCustomer = entityIdOfMention(mention.attrId("company_mention")) != null;
            List<Long> withThisTitle = projectsByTitle.getOrDefault(titleKey(mention.surface), new ArrayList<>());
            if (projectId == null && !hasCustomer && withThisTitle.size() == 1) {
                projectId = withThisTitle.get(0);
            }
            if (projectId == null && !hasCustomer && withThisTitle.isEmpty()
                    && filesByTitleAndCustomer.get(titleAndCustomer(mention)).size() >= 2) {
                projectId = findOrCreateEntity(EntityType.PROJECT, "title:" + titleKey(mention.surface), mention.surface,
                        attributes("title", mention.surface, "source", "title_only"));
            }
            if (projectId == null) {
                assignEntity(mention, null, "unresolved", 0);
            } else {
                assignEntity(mention, projectId, "title_only", 0.6);
            }
        }
    }

    /** "conveyor upgrade|42": a project title and the customer the document names (empty when none). */
    private String titleAndCustomer(MentionRow mention) {
        Long customerId = entityIdOfMention(mention.attrId("company_mention"));
        return titleKey(mention.surface) + "|" + (customerId == null ? "" : customerId);
    }

    /** "Shrink Wrap Retrofit" -> "shrink wrap retrofit": lower case, punctuation as single spaces. */
    private static String titleKey(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Projects of the most similar known title, when it is at least 90% similar (typos in "Job:" fields). */
    private static List<Long> projectsWithSimilarTitle(String titleKey, Map<String, List<Long>> folderProjectsByTitle) {
        String bestTitle = null;
        double bestSimilarity = 0;
        for (String knownTitle : folderProjectsByTitle.keySet()) {
            double similarity = Text.ratio(titleKey, knownTitle);
            if (similarity >= 90 && (similarity > bestSimilarity
                    || (similarity == bestSimilarity && knownTitle.compareTo(bestTitle) > 0))) {
                bestSimilarity = similarity;
                bestTitle = knownTitle;
            }
        }
        return bestTitle == null ? new ArrayList<>() : folderProjectsByTitle.get(bestTitle);
    }

    // ================================================================ people

    /**
     * Gives every person mention its person. A person is identified by name + organisation
     * ("Thomas Bianchi" at Acme), or by e-mail address. Names seen without an organisation
     * (meeting attendees) are matched to a known person when that is unambiguous.
     */
    private void resolvePeople() throws Exception {
        Map<Long, Long> customerOfFolder = customerOfFolderByFile();
        // "name key|organisation id" -> person
        Map<String, Long> personByNameAndOrg = new LinkedHashMap<>();
        // the same with folded keys ("jose muller|42"), tried when the exact key finds no one
        Map<String, Long> personByFoldedNameAndOrg = new HashMap<>();
        Map<String, Long> personByEmail = new HashMap<>();
        List<MentionRow> personMentions = mentionsOfType(EntityType.PERSON);

        // Step 1: people whose organisation is known (e-mail domain, bill-to, vCard, the owner's staff).
        // Mentions with an e-mail address go first, so the same address always finds the same person.
        List<MentionRow> emailFirst = new ArrayList<>(personMentions);
        emailFirst.sort(Comparator.comparing(mention -> mention.attrs.get("email") == null));

        for (MentionRow mention : emailFirst) {
            Long orgId = entityIdOfMention(mention.attrId("org_mention"));
            if (orgId == null) {
                continue;   // handled in step 2
            }
            String nameKey = NameMatcher.personKey(mention.surface);
            String email = mention.attrString("email");

            // same e-mail address, else same name at the same organisation
            Long personId = email != null ? personByEmail.get(email) : null;
            if (personId == null) {
                personId = personByNameAndOrg.get(nameKey + "|" + orgId);
            }
            if (personId == null) {
                personId = personByFoldedNameAndOrg.get(NameMatcher.personKeyFolded(nameKey) + "|" + orgId);
            }
            String method = email != null && personByEmail.containsKey(email) ? "email" : "name+organisation";
            if (personId == null) {
                personId = findOrCreateEntity(EntityType.PERSON, nameKey + "|" + orgId, mention.surface,
                        attributes("company_id", orgId));
                method = "new";
            }
            personByNameAndOrg.put(nameKey + "|" + orgId, personId);
            personByFoldedNameAndOrg.putIfAbsent(NameMatcher.personKeyFolded(nameKey) + "|" + orgId, personId);

            // collect everything known about the person
            if (email != null) {
                personByEmail.put(email, personId);
                addToListAttribute(personId, "emails", email);
            }
            if (mention.attrs.get("job_title") != null) {
                addToListAttribute(personId, "job_titles", mention.attrs.get("job_title"));
            }
            if (mention.attrs.get("phone") != null) {
                addToListAttribute(personId, "phones", mention.attrs.get("phone"));
            }
            assignEntity(mention, personId, method, method.equals("email") || method.equals("new") ? 1.0 : 0.95);
        }

        // name key -> every known person with that name (one per organisation)
        Map<String, Set<KnownPerson>> peopleByName = new HashMap<>();
        for (Map.Entry<String, Long> entry : personByNameAndOrg.entrySet()) {
            int bar = entry.getKey().lastIndexOf('|');
            String nameKey = entry.getKey().substring(0, bar);
            Long orgId = Long.valueOf(entry.getKey().substring(bar + 1));
            addKnownPerson(peopleByName, nameKey, orgId, entry.getValue());
        }

        // Step 2: people seen without an organisation (meeting attendees, signatures with no company line).
        for (MentionRow mention : personMentions) {
            if (mention.entityId != null) {
                continue;   // resolved in step 1
            }
            String nameKey = NameMatcher.personKey(mention.surface);
            Set<KnownPerson> candidates = peopleByName.containsKey(nameKey) ? peopleByName.get(nameKey) : new LinkedHashSet<>();
            // "Jose Muller" for a known "José Müller": the same name with umlauts folded
            if (candidates.isEmpty()) {
                for (Map.Entry<String, Set<KnownPerson>> known : peopleByName.entrySet()) {
                    if (NameMatcher.personKeyFolded(known.getKey()).equals(NameMatcher.personKeyFolded(nameKey))) {
                        candidates.addAll(known.getValue());
                    }
                }
            }

            // "R. Bianchi": compare the initial and surname with every known full name
            String[] initialAndSurname = NameMatcher.personInitialForm(mention.surface);
            if (candidates.isEmpty() && initialAndSurname != null) {
                for (Map.Entry<String, Set<KnownPerson>> known : peopleByName.entrySet()) {
                    String[] words = known.getKey().split(" ");
                    if (words[words.length - 1].equals(initialAndSurname[1]) && known.getKey().startsWith(initialAndSurname[0])) {
                        candidates.addAll(known.getValue());
                    }
                }
            }

            // among several candidates, prefer the one at the customer whose folder holds the file,
            // else the owner's employee
            Long folderCustomer = customerOfFolder.get(mention.fileId);
            List<KnownPerson> preferred = new ArrayList<>();
            for (KnownPerson candidate : candidates) {
                if (candidate.orgId != null && candidate.orgId.equals(folderCustomer)) {
                    preferred.add(candidate);
                }
            }
            if (preferred.isEmpty()) {
                for (KnownPerson candidate : candidates) {
                    if (candidate.orgId != null && candidate.orgId == ownerCompanyId) {
                        preferred.add(candidate);
                    }
                }
            }

            long personId;
            String method;
            double confidence;
            if (candidates.size() == 1) {
                personId = candidates.iterator().next().personId;
                method = "unique_name";
                confidence = 0.8;
            } else if (preferred.size() == 1) {
                personId = preferred.get(0).personId;
                method = "name+context";
                confidence = 0.7;
            } else {
                // unknown, or too many people with this name: a separate person with no organisation
                personId = findOrCreateEntity(EntityType.PERSON, nameKey + "|?", mention.surface, new HashMap<>());
                method = "new_unattributed";
                confidence = 1.0;
                addKnownPerson(peopleByName, nameKey, null, personId);
            }
            assignEntity(mention, personId, method, confidence);
        }
    }

    private static void addKnownPerson(Map<String, Set<KnownPerson>> peopleByName, String nameKey, Long orgId, long personId) {
        if (!peopleByName.containsKey(nameKey)) {
            peopleByName.put(nameKey, new LinkedHashSet<>());
        }
        peopleByName.get(nameKey).add(new KnownPerson(orgId, personId));
    }

    // ================================================================ documents

    /**
     * Gives every document mention its document. The document number is the identity (INV-8034),
     * but the dataset reuses some numbers for different documents (DWG-9296 is two different drawings
     * for two customers), so a number used for several customers is split into one document per customer.
     * Documents without a number are identified by their file.
     */
    private void resolveDocuments() throws Exception {
        // a file's own document mention -> the company (or product) it is about, from its facts
        Map<Long, Long> counterpartyOfDocument = new HashMap<>();
        for (FactRow fact : factsDao.findWithRelations(conn, COUNTERPARTY_RELATIONS)) {
            boolean partyTo = fact.rel == RelationType.PARTY_TO;   // company PARTY_TO contract: reversed direction
            long documentMentionId = partyTo ? fact.dst : fact.src;
            Long counterpartyId = entityIdOfMention(partyTo ? fact.src : fact.dst);
            // the owner is on everything, so it never tells two documents apart
            if (counterpartyId != null && counterpartyId != ownerCompanyId && !counterpartyOfDocument.containsKey(documentMentionId)) {
                counterpartyOfDocument.put(documentMentionId, counterpartyId);
            }
        }
        Map<Long, Long> customerOfFolder = customerOfFolderByFile();

        // document key -> every counterparty seen for it
        List<MentionRow> documentMentions = mentionsOfType(EntityType.DOCUMENT);
        Map<String, Set<Long>> counterpartiesOfKey = new LinkedHashMap<>();
        for (MentionRow mention : documentMentions) {
            if (mention.role == MentionRole.SELF) {
                String key = documentKey(mention);
                if (!counterpartiesOfKey.containsKey(key)) {
                    counterpartiesOfKey.put(key, new LinkedHashSet<>());
                }
                if (counterpartyOfDocument.get(mention.id) != null) {
                    counterpartiesOfKey.get(key).add(counterpartyOfDocument.get(mention.id));
                }
            }
        }

        // a document number with more than one counterparty is really several documents
        Map<String, Set<Long>> reusedNumbers = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : counterpartiesOfKey.entrySet()) {
            if (entry.getValue().size() > 1 && entry.getKey().matches(DOCUMENT_NUMBER)) {
                reusedNumbers.put(entry.getKey(), entry.getValue());
            }
        }

        // A file's own document first, then references to it from other files, so a reference
        // ("Quote Ref: QUO-5238") attaches to the document created from the file.
        List<MentionRow> fileDocumentsFirst = new ArrayList<>(documentMentions);
        fileDocumentsFirst.sort(Comparator.comparing(mention -> mention.role != MentionRole.SELF));

        for (MentionRow mention : fileDocumentsFirst) {
            String key = documentKey(mention);
            Long counterpartyId = null;
            if (reusedNumbers.containsKey(key)) {
                // a reused number: add the counterparty to the key (a reference uses its file's folder customer)
                counterpartyId = mention.role == MentionRole.SELF ? counterpartyOfDocument.get(mention.id)
                        : customerOfFolder.get(mention.fileId);
                if (counterpartyId == null || !reusedNumbers.get(key).contains(counterpartyId)) {
                    assignEntity(mention, null, "ambiguous_number", 0);
                    continue;
                }
                key = key + "@" + counterpartyId;
            }
            long documentId = findOrCreateEntity(EntityType.DOCUMENT, key, mention.surface, null);
            if (mention.role == MentionRole.SELF) {
                mergeDocumentAttributes(documentId, mention);
            }
            String method = key.matches(DOCUMENT_NUMBER) ? "doc_number" : "file_identity";
            assignEntity(mention, documentId, method + (counterpartyId != null ? "+counterparty" : ""),
                    mention.role == MentionRole.SELF ? 1.0 : 0.9);
        }
    }

    /** The document's identity: its number (INV-8034), or "file:&lt;sha&gt;" / "email:..." when it has none. */
    private static String documentKey(MentionRow mention) {
        String key = mention.attrString("key");
        return key != null ? key : mention.surface;
    }

    /**
     * Adds one copy of a document to its entity: the file id, the version name, and the attributes
     * the entity does not have yet. When copies disagree on total, date or job title, both values
     * are kept under "conflicts" (reported later as version_conflict).
     */
    @SuppressWarnings("unchecked")
    private void mergeDocumentAttributes(long documentId, MentionRow mention) throws Exception {
        Map<String, Object> attributes = entityAttributes(documentId);

        List<Object> fileIds = (List<Object>) attributes.get("files");
        if (fileIds == null) {
            fileIds = new ArrayList<>();
            attributes.put("files", fileIds);
        }
        if (!containsNumber(fileIds, mention.fileId)) {
            fileIds.add(mention.fileId);
        }

        for (Map.Entry<String, Object> attribute : mention.attrs.entrySet()) {
            String key = attribute.getKey();
            Object value = attribute.getValue();
            if (key.equals("key") || key.equals("version") || value == null) {
                continue;
            }
            boolean trackConflicts = key.equals("total") || key.equals("date") || key.equals("job_title");
            if (attributes.containsKey(key) && !attributes.get(key).equals(value) && trackConflicts) {
                Map<String, Object> conflicts = (Map<String, Object>) attributes.get("conflicts");
                if (conflicts == null) {
                    conflicts = new LinkedHashMap<>();
                    attributes.put("conflicts", conflicts);
                }
                List<Object> values = (List<Object>) conflicts.get(key);
                if (values == null) {
                    values = new ArrayList<>();
                    values.add(attributes.get(key));
                    conflicts.put(key, values);
                }
                if (!values.contains(value)) {
                    values.add(value);
                }
            } else if (!attributes.containsKey(key) || (key.equals("doc_type") && "other".equals(attributes.get(key)))) {
                // first value wins, except that a real document type replaces "other"
                attributes.put(key, value);
            }
        }

        if (mention.attrs.get("version") != null) {
            List<Object> versions = (List<Object>) attributes.get("versions");
            if (versions == null) {
                versions = new ArrayList<>();
                attributes.put("versions", versions);
            }
            versions.add(mention.attrs.get("version"));
        }
        saveEntityAttributes(documentId, attributes);
    }

    /** Numbers read back from JSON can be Integer or Long, so compare by value. */
    private static boolean containsNumber(List<Object> numbers, long value) {
        for (Object number : numbers) {
            if (((Number) number).longValue() == value) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ products

    /** One product per product code (HL-6200); each is named after its most common line-item description. */
    private void resolveProducts() throws Exception {
        // product id -> description -> how often it was used
        Map<Long, Map<String, Integer>> descriptionCounts = new LinkedHashMap<>();
        for (MentionRow mention : mentionsOfType(EntityType.PRODUCT)) {
            String code = mention.attrString("code") != null ? mention.attrString("code") : mention.surface;
            long productId = findOrCreateEntity(EntityType.PRODUCT, code, code, attributes("code", code));
            if (!mention.surface.equals(code)) {
                if (!descriptionCounts.containsKey(productId)) {
                    descriptionCounts.put(productId, new LinkedHashMap<>());
                }
                Map<String, Integer> counts = descriptionCounts.get(productId);
                counts.put(mention.surface, counts.containsKey(mention.surface) ? counts.get(mention.surface) + 1 : 1);
            }
            assignEntity(mention, productId, "product_code", mention.confidence);
        }

        for (Map.Entry<Long, Map<String, Integer>> product : descriptionCounts.entrySet()) {
            String mostCommon = null;
            for (Map.Entry<String, Integer> description : product.getValue().entrySet()) {
                if (mostCommon == null || description.getValue() > product.getValue().get(mostCommon)) {
                    mostCommon = description.getKey();
                }
            }
            entitiesDao.updateName(conn, product.getKey(), mostCommon);
        }
    }

    // ================================================================ write-back

    /** Writes every mention's entity, method and confidence, then recomputes the aliases table from them. */
    private void saveResolutions() throws Exception {
        for (MentionRow mention : mentionsById.values()) {
            mentionsDao.updateResolution(conn, mention);
        }
        aliasesDao.rebuild(conn);
        Db.commit(conn);
    }

    // ================================================================ helpers

    /**
     * Returns the entity with this type and key, creating it when it does not exist yet.
     * Non-null attributes are added to the entity (and replace existing values with the same name).
     */
    private long findOrCreateEntity(EntityType type, String key, String name, Map<String, Object> attributes) throws Exception {
        EntityRow existing = entitiesDao.findByTypeAndKey(conn, type, key);
        if (existing != null) {
            if (attributes != null && !attributes.isEmpty()) {
                Map<String, Object> merged = existing.attrs;
                for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
                    if (attribute.getValue() != null) {
                        merged.put(attribute.getKey(), attribute.getValue());
                    }
                }
                entitiesDao.updateAttrs(conn, existing.id, merged);
            }
            return existing.id;
        }
        Map<String, Object> nonNullAttributes = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
                if (attribute.getValue() != null) {
                    nonNullAttributes.put(attribute.getKey(), attribute.getValue());
                }
            }
        }
        return entitiesDao.insert(conn, type, name, key, nonNullAttributes);
    }

    private Map<String, Object> entityAttributes(long entityId) throws Exception {
        return entitiesDao.findById(conn, entityId).attrs;
    }

    private void saveEntityAttributes(long entityId, Map<String, Object> attributes) throws Exception {
        entitiesDao.updateAttrs(conn, entityId, attributes);
    }

    /** Adds a value to a list attribute of an entity (e.g. a person's "emails"), once. */
    @SuppressWarnings("unchecked")
    private void addToListAttribute(long entityId, String key, Object value) throws Exception {
        Map<String, Object> attributes = entityAttributes(entityId);
        List<Object> values = (List<Object>) attributes.get(key);
        if (values == null) {
            values = new ArrayList<>();
            attributes.put(key, values);
        }
        if (!values.contains(value)) {
            values.add(value);
            saveEntityAttributes(entityId, attributes);
        }
    }

    /** Records the decision for one mention (in memory; saveResolutions() writes it). */
    private void assignEntity(MentionRow mention, Long entityId, String method, double confidence) {
        mention.entityId = entityId;
        mention.method = method;
        mention.confidence = confidence;
        countMethod(mention.etype, method, 1);
    }

    /** Adds {@code delta} to the stage summary's count of mentions of this type resolved by this method. */
    private void countMethod(EntityType etype, String method, int delta) {
        String countKey = etype.value() + ":" + method;
        int count = methodCounts.getOrDefault(countKey, 0) + delta;
        if (count == 0) {
            methodCounts.remove(countKey);
        } else {
            methodCounts.put(countKey, count);
        }
    }

    /** The mentions of one entity type, in id order. */
    private List<MentionRow> mentionsOfType(EntityType type) {
        List<MentionRow> ofType = new ArrayList<>();
        for (MentionRow mention : mentionsById.values()) {
            if (mention.etype == type) {
                ofType.add(mention);
            }
        }
        return ofType;
    }

    /** file id -> the company of the customer folder the file sits in. */
    private Map<Long, Long> customerOfFolderByFile() {
        Map<Long, Long> customerOfFolder = new HashMap<>();
        for (MentionRow mention : mentionsOfType(EntityType.COMPANY)) {
            if (mention.role == MentionRole.FOLDER) {
                customerOfFolder.put(mention.fileId, mention.entityId);
            }
        }
        return customerOfFolder;
    }

    /** The entity a mention was resolved to, or null (also when the mention id is null). */
    private Long entityIdOfMention(Long mentionId) {
        MentionRow mention = mentionId == null ? null : mentionsById.get(mentionId);
        return mention == null ? null : mention.entityId;
    }

    /** attributes("role", "owner", "domain", x) -> {role: owner, domain: x}, keeping the order. */
    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            attributes.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return attributes;
    }
}
