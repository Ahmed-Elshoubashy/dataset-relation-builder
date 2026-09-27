package com.dubsof.graph.pipeline;

import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.read.TextSource;
import com.dubsof.graph.resolve.NameMatcher;

import java.sql.Connection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out whose file share this is, from three signals:
 * <ol>
 *   <li>the letterhead: the organisation on the first line of most generated PDFs;</li>
 *   <li>the e-mail domain on the most e-mails, as sender or recipient: the owner's people are on nearly
 *       every e-mail in its own file share (free providers like gmail.com left out);</li>
 *   <li>with no letterhead: the organisation named in the most files, preferring one that matches
 *       the sender domain (a small dataset rarely has enough PDFs for a letterhead).</li>
 * </ol>
 * Finds no owner at all when none of them gives an answer.
 */
public final class OwnerDetector {

    /** A letterhead must be the first line of at least this many PDFs (and 10% of them). */
    private static final int MIN_LETTERHEAD_PDFS = 5;
    /** An organisation must be named in at least this many files to be taken as the owner. */
    private static final int MIN_FILES_NAMING_OWNER = 2;

    /** A From/To/Cc header line. */
    private static final Pattern ADDRESS_HEADER = Pattern.compile("^(From|To|Cc): (.*)$", Pattern.MULTILINE);
    private static final Pattern DOMAIN = Pattern.compile("@([\\w-]+(?:\\.[\\w-]+)+)");

    private static final FilesDao filesDao = new FilesDao();

    private OwnerDetector() {
    }

    public static Owner detect(Connection conn, NameMatcher names) throws Exception {
        String domain = mostCommonEmailDomain(conn, names);
        String name = letterhead(conn, names);
        if (name == null) {
            name = mostNamedOrganisation(conn, names, domain);
        }
        if (name == null) {
            name = domain;   // no organisation name found: name the owner after its e-mail domain
        }
        return new Owner(name, domain);
    }

    /** The first line shared by most native PDFs, when it looks like a company name. */
    private static String letterhead(Connection conn, NameMatcher names) throws Exception {
        Map<String, Integer> firstLines = new LinkedHashMap<>();
        int pdfs = 0;
        for (FileRow f : filesDao.findTexts(conn, FileKind.PDF, FileStatus.OK, TextSource.NATIVE)) {
            for (String line : f.text.split("\\r?\\n")) {
                if (!line.trim().isEmpty()) {
                    count(firstLines, line.trim());
                    pdfs++;
                    break;
                }
            }
        }
        String name = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : firstLines.entrySet()) {
            if (e.getValue() > bestCount && e.getValue() >= Math.max(MIN_LETTERHEAD_PDFS, 0.1 * pdfs)
                    && names.hasLegalSuffix(e.getKey())) {
                name = e.getKey();
                bestCount = e.getValue();
            }
        }
        return name;
    }

    /**
     * The domain on the most e-mails (in From, To or Cc). Ties go to the domain that sent more of them,
     * then to the alphabetically first, so the answer never depends on file order.
     */
    private static String mostCommonEmailDomain(Connection conn, NameMatcher names) throws Exception {
        Map<String, Integer> emailsWithDomain = new TreeMap<>();
        Map<String, Integer> emailsSentFromDomain = new HashMap<>();
        for (FileRow f : filesDao.findTexts(conn, FileKind.EML, FileStatus.OK, null)) {
            int headerEnd = f.text.indexOf("\n\n");
            String headers = headerEnd < 0 ? f.text : f.text.substring(0, headerEnd);
            Set<String> inThisEmail = new HashSet<>();
            for (Matcher header = ADDRESS_HEADER.matcher(headers); header.find(); ) {
                for (Matcher d = DOMAIN.matcher(header.group(2)); d.find(); ) {
                    String domain = d.group(1).toLowerCase();
                    if (names.isGenericDomain(domain)) {
                        continue;
                    }
                    if (inThisEmail.add(domain)) {
                        count(emailsWithDomain, domain);
                    }
                    if (header.group(1).equals("From")) {
                        count(emailsSentFromDomain, domain);
                    }
                }
            }
        }
        String best = null;
        for (Map.Entry<String, Integer> e : emailsWithDomain.entrySet()) {
            int sent = emailsSentFromDomain.getOrDefault(e.getKey(), 0);
            if (best == null || e.getValue() > emailsWithDomain.get(best)
                    || (e.getValue().equals(emailsWithDomain.get(best)) && sent > emailsSentFromDomain.getOrDefault(best, 0))) {
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * The organisation (a capitalised name ending in a legal suffix, e.g. "Harbor Robotics Inc") named in
     * the most files. With a sender domain, only names that match it count, so a busy customer is not
     * mistaken for the owner.
     */
    private static String mostNamedOrganisation(Connection conn, NameMatcher names, String domain) throws Exception {
        Map<String, Integer> filesNaming = new HashMap<>();     // company key -> number of files naming it
        Map<String, String> firstSpelling = new HashMap<>();    // company key -> the first spelling seen
        for (FileRow f : filesDao.findWithText(conn, FileStatus.OK)) {
            Set<String> inThisFile = new HashSet<>();
            for (String name : names.findCompanyNames(f.text)) {
                String key = names.companyKey(name);
                if (inThisFile.add(key)) {
                    count(filesNaming, key);
                    if (!firstSpelling.containsKey(key)) {
                        firstSpelling.put(key, name);
                    }
                }
            }
        }
        String bestKey = null;
        for (Map.Entry<String, Integer> e : filesNaming.entrySet()) {
            String name = firstSpelling.get(e.getKey());
            boolean matchesDomain = domain == null || names.matchDomain(domain, name) != null;
            boolean better = bestKey == null || e.getValue() > filesNaming.get(bestKey)
                    || (e.getValue().equals(filesNaming.get(bestKey)) && e.getKey().compareTo(bestKey) < 0);
            if (matchesDomain && e.getValue() >= MIN_FILES_NAMING_OWNER && better) {
                bestKey = e.getKey();
            }
        }
        return bestKey == null ? null : firstSpelling.get(bestKey);
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
    }
}
