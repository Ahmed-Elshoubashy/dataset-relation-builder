package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.Ingestor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Patterns and helpers shared by the parsers and the Extractor. */
public final class ParserUtils {

    public static final String DATE = "\\d{1,2} [A-Z][a-z]{2} \\d{4}";
    public static final Pattern DOC_NO = Pattern.compile("\\b(INV|QUO|PO|DN|DWG|CAL|SPEC|DS|ISO)-(\\d{3,6})\\b");
    public static final Pattern JOB_ID = Pattern.compile("\\bJOB-\\d{4}-\\d{4}\\b");
    /** Equipment/part codes like HL-6200, VFD-15, SM-750 (document-number prefixes excluded). */
    public static final Pattern PRODUCT_CODE = Pattern.compile("\\b(?!(?:INV|QUO|PO|DN|DWG|CAL|SPEC|DS|ISO|JOB|SN)-)([A-Z]{2,4}-\\d{2,4})\\b");
    /** Document-number prefix -> document type. */
    public static final Map<String, String> PREFIX_TYPES = new HashMap<>();

    static {
        PREFIX_TYPES.put("INV", "invoice");
        PREFIX_TYPES.put("QUO", "quote");
        PREFIX_TYPES.put("PO", "purchase_order");
        PREFIX_TYPES.put("DN", "delivery_note");
        PREFIX_TYPES.put("DWG", "drawing");
        PREFIX_TYPES.put("CAL", "calibration_cert");
        PREFIX_TYPES.put("SPEC", "specification");
        PREFIX_TYPES.put("DS", "datasheet");
        PREFIX_TYPES.put("ISO", "iso_certificate");
    }

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

    /** Cross-references anywhere in the text: document numbers and job codes. */
    public static void refs(Extraction ex, String text, String skipKey) {
        Matcher m = DOC_NO.matcher(text);
        while (m.find()) {
            String key = m.group();
            String prefix = m.group(1);
            if (key.equals(skipKey) || prefix.equals("SPEC") || prefix.equals("DS") || prefix.equals("ISO")) {
                continue;
            }
            ex.fact(ex.doc, RelationType.REFERENCES, ex.addMentionWithConfidence(EntityType.DOCUMENT, key, MentionRole.REFERENCE, 0.9, "key", key, "doc_type", PREFIX_TYPES.get(prefix)));
        }
        Matcher j = JOB_ID.matcher(text);
        while (j.find()) {
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

    public static Integer owner(Extraction ex) {
        return ex.addMentionWithConfidence(EntityType.COMPANY, Config.ownerName, MentionRole.IMPLIED_OWNER, 0.8);
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
        return n.matches("^[A-Z][A-Za-z'’-]*\\.?(?: [A-Z][A-Za-z'’-]*\\.?){1,3}$") ? n : null;
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

    /** INV-8002_Acme Corporation -> number "INV-8002", type "invoice", company "Acme Corporation"; all null when not numbered. */
    public static FilenameDocument filenameDoc(String stem) {
        Matcher m = Pattern.compile("^(INV|QUO|PO|DN|DWG|CAL)-(\\d+)(?:[_ ](.*))?$").matcher(stem);
        if (!m.matches()) {
            return new FilenameDocument(null, null, null);
        }
        String rest = m.group(3) == null ? "" : m.group(3).trim();
        String company = null;
        if (!rest.isEmpty() && !Pattern.compile("^(Rev\\w+|Calibration|v\\d+|FINAL|revised.*)$", Pattern.CASE_INSENSITIVE).matcher(rest).matches()) {
            company = rest;
        }
        return new FilenameDocument(m.group(1) + "-" + m.group(2), PREFIX_TYPES.get(m.group(1)), company);
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
}
