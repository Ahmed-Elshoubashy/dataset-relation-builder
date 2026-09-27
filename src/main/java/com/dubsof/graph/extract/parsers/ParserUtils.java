package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.Ingestor;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Patterns and helpers shared by the parsers and the Extractor. */
public final class ParserUtils {

    public static final String DATE = "\\d{1,2} [A-Z][a-z]{2} \\d{4}";

    private ParserUtils() {
    }

    /** Adds the file's own document mention. {@code attrs} are extra key/value pairs. */
    public static Integer document(Extraction ex, FileRow row, String docType, String key, String title, Object... attrs) {
        String stem = stem(row.path);
        if (key == null) {
            key = "file:" + row.sha256.substring(0, 16);
        }
        Object[] all = new Object[attrs.length + 6];
        all[0] = "key";
        all[1] = key;
        all[2] = "doc_type";
        all[3] = docType == null ? "other" : docType;
        all[4] = "version";
        all[5] = baseStem(stem).equals(stem) ? null : stem;
        System.arraycopy(attrs, 0, all, 6, attrs.length);
        ex.doc = ex.addMention(EntityType.DOCUMENT, title != null ? title : key, MentionRole.SELF, all);
        return ex.doc;
    }

    /** Cross-references anywhere in the text: document numbers (see DocumentNumbers) and job ids. */
    public static void refs(Extraction ex, String text, String skipKey) {
        for (DocumentNumbers.Found found : ex.documentNumbers.references(text)) {
            if (found.number.equals(skipKey)) {
                continue;
            }
            ex.fact(ex.doc, RelationType.REFERENCES, ex.addMentionWithConfidence(EntityType.DOCUMENT, found.number,
                    MentionRole.REFERENCE, 0.9, "key", found.number, "doc_type", found.docType));
        }
        Pattern jobIdPattern = ex.dataset.profile.jobIdPattern;   // null: this dataset has no job ids
        Matcher j = jobIdPattern == null ? null : jobIdPattern.matcher(text);
        while (j != null && j.find()) {
            ex.fact(ex.addMentionWithConfidence(EntityType.PROJECT, j.group(), MentionRole.REFERENCE, 0.9, "job_id", j.group()), RelationType.HAS_DOCUMENT, ex.doc);
        }
    }

    /**
     * "Label: value" at the start of a line, or mid-line with a colon: OCR often merges
     * side-by-side columns ("CV6 4LP Date: 12 Oct 2023").
     */
    public static String field(String text, String label, String value) {
        Pattern p = Pattern.compile("(?:^\\s*" + label + "\\s*:?|(?<=\\s)" + label + "\\s*:)\\s*" + value + "\\s*$",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    public static Integer personWithOrg(Extraction ex, String name, MentionRole role, Integer org, Object... attrs) {
        String clean = cleanPerson(name);
        if (clean == null) {
            return null;
        }
        Object[] all = new Object[attrs.length + 2];
        all[0] = "org_mention";
        all[1] = org;
        System.arraycopy(attrs, 0, all, 2, attrs.length);
        Integer p = ex.addMention(EntityType.PERSON, clean, role, all);
        ex.fact(p, RelationType.WORKS_FOR, org);
        return p;
    }

    /** The owner, as the employer of staff on documents it generates; null when the dataset has no known owner. */
    public static Integer owner(Extraction ex) {
        if (!ex.dataset.owner.isKnown()) {
            return null;
        }
        return ex.addMentionWithConfidence(EntityType.COMPANY, ex.dataset.owner.name, MentionRole.IMPLIED_OWNER, 0.8);
    }

    public static Integer companyFromDomain(Extraction ex, String addr) {
        String domain = addr.substring(addr.lastIndexOf('@') + 1).toLowerCase();
        return ex.addMentionWithConfidence(EntityType.COMPANY, domain, MentionRole.EMAIL_DOMAIN, 0.9, "domain", domain);
    }

    public static String cleanPerson(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().replaceFirst("(?i)^(Mr|Mrs|Ms|Miss|Dr)\\.?\\s+", "").replaceFirst("[_\\s]+$", "");
        // two to four capitalised words, in any alphabet ("José Müller", "Zoë O'Neill")
        return n.matches("^\\p{Lu}[\\p{L}\\p{M}'’-]*\\.?(?: \\p{Lu}[\\p{L}\\p{M}'’-]*\\.?){1,3}$") ? n : null;
    }

    /** File name without folder and extension. */
    public static String stem(String path) {
        String[] members = path.split(Pattern.quote(Ingestor.MEMBER_SEP));
        String name = members[members.length - 1];
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
    }

    /** 'QUO-5238 FINAL' / 'QUO-5238_v2' / 'Contract_X__2' / 'y (1)' -> version-free stem. */
    public static String baseStem(String stem) {
        String s = stem.replaceFirst("\\s*\\(\\d+\\)$", "");
        s = s.replaceFirst("(__\\d+|[_ -]v\\d+|[_ -]?(FINAL|final|revised|copy|draft))$", "");
        return s.replaceAll("^[ _-]+|[ _-]+$", "");
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** The addresses in a From / To / Cc header; none when it cannot be parsed. */
    public static List<InternetAddress> addresses(String header) {
        List<InternetAddress> out = new ArrayList<>();
        if (header == null) {
            return out;
        }
        try {
            for (InternetAddress a : InternetAddress.parseHeader(header, false)) {
                out.add(a);
            }
        } catch (AddressException e) {
            // unparseable header: no people from it
        }
        return out;
    }

    /** "isla.patel@x.com" -> "Isla Patel" */
    public static String nameFromAddress(String addr) {
        List<String> words = new ArrayList<>();
        for (String w : addr.substring(0, addr.indexOf('@')).split("\\.")) {
            words.add(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase());
        }
        return join(words, " ");
    }
}
