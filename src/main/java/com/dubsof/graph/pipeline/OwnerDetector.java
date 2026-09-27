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
 * Works out whose file share this is, from two kinds of evidence that only the owner leaves:
 * <ol>
 *   <li>the letterhead: the organisation on the first line of many generated PDFs;</li>
 *   <li>the mailbox: the e-mail domain on most e-mails, as sender or recipient (the owner's people are on
 *       nearly every e-mail in its own file share; free providers like gmail.com are left out).</li>
 * </ol>
 * With a domain but no letterhead, the owner is the organisation named in the most files that matches the
 * domain, else the domain itself. When the letterhead and the domain disagree, the letterhead wins and the
 * domain is dropped.
 *
 * Being the owner is not harmless: its name is left out of MENTIONS, document counterparties and the
 * gazetteer, so a wrong owner hides a real customer's links. That is why both signals need a minimum,
 * and a company that is merely mentioned often never becomes the owner. Without either signal there is no owner.
 */
public final class OwnerDetector {

    /** A letterhead must be the first line of at least this many PDFs (and 10% of them). */
    private static final int MIN_LETTERHEAD_PDFS = 5;
    /** The owner's domain must be on at least this many e-mails... */
    private static final int MIN_EMAILS_WITH_DOMAIN = 5;
    /** ...and on at least this share of the e-mails that have a company (not free-mail) domain. */
    private static final double MIN_SHARE_OF_EMAILS = 0.3;
    /** An organisation must be named in at least this many files to be taken as the owner. */
    private static final int MIN_FILES_NAMING_OWNER = 2;

    /** A From/To/Cc header line. */
    private static final Pattern ADDRESS_HEADER = Pattern.compile("^(From|To|Cc): (.*)$", Pattern.MULTILINE);
    private static final Pattern DOMAIN = Pattern.compile("@([\\w-]+(?:\\.[\\w-]+)+)");

    private static final FilesDao filesDao = new FilesDao();

    /** A letterhead and the number of PDFs it heads. */
    private static class Letterhead {
        final String name;
        final int pdfs;

        Letterhead(String name, int pdfs) {
            this.name = name;
            this.pdfs = pdfs;
        }
    }

    /** The best owner-domain candidate: how many e-mails it is on, out of those with a company domain. */
    private static class MailDomain {
        final String domain;
        final int emails;
        final int emailsWithCompanyDomain;

        MailDomain(String domain, int emails, int emailsWithCompanyDomain) {
            this.domain = domain;
            this.emails = emails;
            this.emailsWithCompanyDomain = emailsWithCompanyDomain;
        }

        double share() {
            return emails / (double) emailsWithCompanyDomain;
        }

        boolean enough() {
            return emails >= MIN_EMAILS_WITH_DOMAIN && share() >= MIN_SHARE_OF_EMAILS;
        }
    }

    private OwnerDetector() {
    }

    /**
     * The owner, with the evidence as its reason ("letterhead on 212 PDFs; domain on 64% of e-mails (310)"),
     * or no owner with the reason why not.
     */
    public static Owner detect(Connection conn, NameMatcher names) throws Exception {
        Letterhead letterhead = letterhead(conn, names);
        MailDomain mail = mostCommonEmailDomain(conn, names);
        String domain = mail != null && mail.enough() ? mail.domain : null;

        String name = null;
        StringBuilder reason = new StringBuilder();
        if (letterhead != null) {
            name = letterhead.name;
            reason.append("letterhead on ").append(letterhead.pdfs).append(" PDFs");
            if (domain != null && names.matchDomain(domain, name) == null) {
                reason.append("; ignored ").append(domain).append(", which does not match it");
                domain = null;
            }
        } else if (domain != null) {
            name = mostNamedOrganisation(conn, names, domain);
            if (name == null) {
                name = domain;   // no organisation name found: name the owner after its e-mail domain
            }
        }
        if (domain != null) {
            reason.append(reason.length() == 0 ? "" : "; ")
                    .append(String.format("domain on %.0f%% of e-mails (%d)", mail.share() * 100, mail.emails));
        }
        if (name == null) {
            String mailPart = mail == null ? "no company e-mail domain"
                    : String.format("no domain on %d+ e-mails and %.0f%% of them (best: %s on %d, %.0f%%)",
                    MIN_EMAILS_WITH_DOMAIN, MIN_SHARE_OF_EMAILS * 100, mail.domain, mail.emails, mail.share() * 100);
            return new Owner(null, null, "no letterhead on " + MIN_LETTERHEAD_PDFS + "+ PDFs, " + mailPart);
        }
        return new Owner(name, domain, reason.toString());
    }

    /** The first line shared by most native PDFs, when it looks like a company name; null when there is none. */
    private static Letterhead letterhead(Connection conn, NameMatcher names) throws Exception {
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
        Letterhead best = null;
        for (Map.Entry<String, Integer> e : firstLines.entrySet()) {
            if ((best == null || e.getValue() > best.pdfs) && e.getValue() >= Math.max(MIN_LETTERHEAD_PDFS, 0.1 * pdfs)
                    && names.hasLegalSuffix(e.getKey())) {
                best = new Letterhead(e.getKey(), e.getValue());
            }
        }
        return best;
    }

    /**
     * The domain on the most e-mails (in From, To or Cc), with its counts, even below the minimums (the log
     * says how close it came); null without any company domain. Ties go to the domain that sent more of
     * them, then to the alphabetically first, so the answer never depends on file order.
     */
    private static MailDomain mostCommonEmailDomain(Connection conn, NameMatcher names) throws Exception {
        Map<String, Integer> emailsWithDomain = new TreeMap<>();
        Map<String, Integer> emailsSentFromDomain = new HashMap<>();
        int emailsWithCompanyDomain = 0;
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
            if (!inThisEmail.isEmpty()) {
                emailsWithCompanyDomain++;
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
        return best == null ? null : new MailDomain(best, emailsWithDomain.get(best), emailsWithCompanyDomain);
    }

    /**
     * The organisation (a capitalised name ending in a legal suffix, e.g. "Harbor Robotics Inc") named in the
     * most files among those that match the owner's domain, so a busy customer is not mistaken for the owner.
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
            boolean better = bestKey == null || e.getValue() > filesNaming.get(bestKey)
                    || (e.getValue().equals(filesNaming.get(bestKey)) && e.getKey().compareTo(bestKey) < 0);
            if (names.matchDomain(domain, name) != null && e.getValue() >= MIN_FILES_NAMING_OWNER && better) {
                bestKey = e.getKey();
            }
        }
        return bestKey == null ? null : firstSpelling.get(bestKey);
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
    }
}
