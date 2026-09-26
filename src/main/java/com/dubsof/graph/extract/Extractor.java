package com.dubsof.graph.extract;

import com.dubsof.graph.Config;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.ingest.Ingestor;
import com.dubsof.graph.util.Json;
import com.dubsof.graph.util.Text;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 3: turn each file into mentions (raw references to entities) and facts
 * (typed links between mentions of the same file).
 *
 * Nothing here decides identity: "Acme Corp." and "ACME Corp" are just two company
 * mentions. The Resolver clusters mentions into entities afterwards.
 */
public class Extractor {

    static final String DATE = "\\d{1,2} [A-Z][a-z]{2} \\d{4}";
    static final Pattern DOC_NO = Pattern.compile("\\b(INV|QUO|PO|DN|DWG|CAL|SPEC|DS|ISO)-(\\d{3,6})\\b");
    static final Pattern JOB_ID = Pattern.compile("\\bJOB-\\d{4}-\\d{4}\\b");
    /** Equipment/part codes like HL-6200, VFD-15, SM-750 (document-number prefixes excluded). */
    static final Pattern PRODUCT_CODE = Pattern.compile("\\b(?!(?:INV|QUO|PO|DN|DWG|CAL|SPEC|DS|ISO|JOB|SN)-)([A-Z]{2,4}-\\d{2,4})\\b");
    /** A currency sign: '£', or the garbled '·' / '?' seen in some exports. */
    static final String CUR = "[^\\d\\s|]?\\s?";
    static final String MONEY = CUR + "([\\d,]+\\.\\d{2})";
    static final Pattern LINE_ITEM = Pattern.compile("^(?<desc>.+?)\\s+(?<qty>\\d+)\\s+(?<unit>[a-z]+)\\s+" + CUR
            + "(?<price>[\\d,]+\\.\\d{2})\\s+" + CUR + "(?<total>[\\d,]+\\.\\d{2})\\s*$");
    static final Pattern TOTAL_LINE = Pattern.compile("^(Subtotal|Tax \\(\\d+%\\)|TOTAL)\\s*:?\\s*" + MONEY + "$", Pattern.CASE_INSENSITIVE);
    static final Pattern SIGNOFF = Pattern.compile("^(Yours faithfully|Yours sincerely|Kind regards|Best regards|Regards|Many thanks),?$",
            Pattern.CASE_INSENSITIVE);
    static final Map<String, String> DOC_TYPES = new LinkedHashMap<String, String>();
    static final Map<String, String> PREFIX_TYPES = new HashMap<String, String>();

    static {
        DOC_TYPES.put("INVOICE", "invoice");
        DOC_TYPES.put("QUOTATION", "quote");
        DOC_TYPES.put("PURCHASE ORDER", "purchase_order");
        DOC_TYPES.put("DELIVERY NOTE", "delivery_note");
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

    /** One parser per document template. Returns true when it recognised the file. */
    interface Parser {
        boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project);
    }

    private final Parser[] parsers = {
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return email(ex, r, t, fc); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return vcard(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return calendar(ex, r, t, fc); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return businessDoc(ex, r, t, fc); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return drawing(ex, r, t, fc); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return calibration(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return spec(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return manual(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return certificate(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return letter(ex, r, t, fc); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return contract(ex, r, t, fc); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return report(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return serviceReport(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return meetingNotes(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return itemList(ex, r, t); } },
        new Parser() { public boolean parse(Extraction ex, FileRow r, String t, Integer fc, Integer p) { return screenshotTable(ex, r, t); } },
    };

    // ================================================================ per file

    public Extraction extractFile(FileRow row) {
        Extraction ex = new Extraction(row.id);
        Integer[] ctx = folderContext(ex, row);
        Integer folderCompany = ctx[0];
        Integer project = ctx[1];
        String text = row.text == null ? "" : row.text;
        String stem = stem(row.path);

        if (row.status == FileStatus.OK) {
            for (Parser parser : parsers) {
                if (parser.parse(ex, row, text, folderCompany, project)) {
                    break;
                }
            }
        }
        if (ex.doc == null) {
            // Unread (needs OCR / corrupt / empty) or unrecognised: fall back to the filename.
            String[] fd = filenameDoc(stem);
            if (row.status != FileStatus.OK || fd[0] != null || !Text.isBlank(text)) {
                document(ex, row, fd[1], fd[0], fd[0] != null ? fd[0] : stem,
                        "unread", row.status == FileStatus.OK ? null : Boolean.TRUE);
            }
        }
        if (ex.doc == null) {
            return ex;   // blank, unrecognised file: nothing to link
        }
        // Filename hints: 'INV-8002_Acme Corporation', 'DN-6041_Whitmore', 'GB-40_—_Datasheet_2'
        String filenameCompany = filenameDoc(stem)[2];
        if (filenameCompany != null) {
            Integer c = ex.mc("company", filenameCompany, "filename", 0.7, "truncated", Boolean.TRUE);
            if (PREFIX_TYPES.containsValue(ex.docMention().attrs.get("doc_type"))) {
                ex.fact(ex.doc, "ISSUED_TO", c);
            }
        }
        Matcher pm = Pattern.compile("^([A-Z]{2,4}-\\d{2,4})(?:-\\d+)?_").matcher(stem);
        if (pm.lookingAt() && !Pattern.compile("^(INV|QUO|PO|DN|DWG|CAL)-").matcher(stem).lookingAt()) {
            ex.fact(ex.doc, "DESCRIBES", ex.mc("product", pm.group(1), "filename", 0.8, "code", pm.group(1)));
        }
        ex.fact(project, "HAS_DOCUMENT", ex.doc);
        if (project == null) {
            ex.fact(ex.doc, "FILED_UNDER", folderCompany);
        }
        if (!text.isEmpty() && !"email".equals(ex.docMention().attrs.get("doc_type"))) {
            refs(ex, text, (String) ex.docMention().attrs.get("key"));
        }
        return ex;
    }

    private Integer[] folderContext(Extraction ex, FileRow row) {
        Integer company = null;
        Integer project = null;
        if (row.folderCompany != null) {
            company = ex.m("company", row.folderCompany, "folder");
        }
        if (row.folderJob != null) {
            Matcher jm = Ingestor.JOB_DIR.matcher(row.folderJob);
            if (jm.matches()) {
                project = ex.m("project", jm.group(2), "folder", "job_id", jm.group(1), "company_mention", company);
                ex.fact(company, "HAS_PROJECT", project);
            }
        }
        return new Integer[] {company, project};
    }

    /** Adds the file's own document mention. {@code attrs} are extra key/value pairs. */
    private Integer document(Extraction ex, FileRow row, String docType, String key, String title, Object... attrs) {
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
        ex.doc = ex.m("document", title != null ? title : key, "self", all);
        return ex.doc;
    }

    // ================================================================ parsers

    private boolean businessDoc(Extraction ex, FileRow row, String text, Integer folderCompany) {
        List<String> rawLines = Text.lines(text);
        String header = null;
        for (String l : rawLines.subList(0, Math.min(15, rawLines.size()))) {
            for (Map.Entry<String, String> t : DOC_TYPES.entrySet()) {
                if (header == null && (l.equals(t.getKey()) || l.endsWith(" " + t.getKey()))) {
                    header = t.getValue();
                }
            }
        }
        String number = field(text, "No", "((?:INV|QUO|PO|DN)-\\d+)");
        if (header == null && number == null) {
            return false;
        }
        String[] fd = filenameDoc(stem(row.path));
        String key = number != null ? number : fd[0];
        String docType = header != null ? header : fd[1];
        if (fd[0] != null && number != null && !fd[0].equals(number)) {
            ex.issue("filename_mismatch", "warn", "filename says " + fd[0] + " but document says " + number);
        }
        String date = field(text, "Date", "(" + DATE + ")");
        String job = field(text, "Job", "(.+)");
        String quoteRef = field(text, "Quote Ref", "(QUO-\\d+)");

        List<String> cleaned = new ArrayList<String>();
        for (String l : rawLines) {
            cleaned.add(l.replace(" | ", " "));
        }
        List<String> lines = joinTableRows(cleaned);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Map<String, Double> totals = new HashMap<String, Double>();
        for (String l : lines) {
            Matcher m = LINE_ITEM.matcher(l);
            Matcher t = TOTAL_LINE.matcher(l);
            if (m.matches() && !l.toLowerCase().startsWith("description")) {
                Map<String, Object> it = new LinkedHashMap<String, Object>();
                it.put("desc", m.group("desc"));
                it.put("qty", Integer.parseInt(m.group("qty")));
                it.put("unit", m.group("unit"));
                it.put("price", money(m.group("price")));
                it.put("total", money(m.group("total")));
                items.add(it);
            } else if (t.matches()) {
                totals.put(t.group(1).split(" ")[0].toLowerCase(), money(t.group(2)));
            }
        }
        if (Pattern.compile("·[\\d,]+\\.\\d{2}").matcher(text).find()) {
            ex.issue("encoding", "info", "currency symbol garbled ('·' instead of '£')");
        }
        double sum = 0;
        for (Map<String, Object> it : items) {
            int qty = (Integer) it.get("qty");
            double price = (Double) it.get("price");
            double total = (Double) it.get("total");
            sum += total;
            if (Math.abs(qty * price - total) > 0.02) {
                ex.issue("arithmetic", "warn", String.format("%s: %d x %.2f != %.2f", it.get("desc"), qty, price, total));
            }
        }
        if (!items.isEmpty() && totals.containsKey("subtotal") && Math.abs(sum - totals.get("subtotal")) > 0.02) {
            ex.issue("arithmetic", "warn", String.format("line totals sum to %.2f, subtotal says %.2f", sum, totals.get("subtotal")));
        }

        Integer doc = document(ex, row, docType, key, key, "date", date, "job_title", job,
                "total", totals.get("total"), "subtotal", totals.get("subtotal"),
                "line_items", items.isEmpty() ? null : items);

        // Bill-to block: company, address lines, Attn
        Integer billCompany = null;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toUpperCase().startsWith("BILL TO")) {
                List<String> block = new ArrayList<String>();
                for (String next : lines.subList(i + 1, lines.size())) {
                    String low = next.toLowerCase();
                    if (low.startsWith("attn") || low.startsWith("description")) {
                        break;
                    }
                    block.add(next);
                }
                if (!block.isEmpty()) {
                    String address = join(block.subList(1, block.size()), ", ");
                    billCompany = ex.m("company", block.get(0), "bill_to", "address", address.isEmpty() ? null : address);
                }
                break;
            }
        }
        ex.fact(doc, "ISSUED_TO", billCompany);
        String attn = field(text, "Attn", "(.+)");
        if (attn != null) {
            ex.fact(doc, "ATTENTION_OF", personWithOrg(ex, attn, "attn", billCompany));
        }
        if (job != null) {
            Integer pj = ex.mc("project", job, "doc_job_field", 0.9,
                    "company_mention", billCompany != null ? billCompany : folderCompany);
            ex.fact(pj, "HAS_DOCUMENT", doc);
        }
        if (quoteRef != null) {
            ex.fact(doc, "REFERENCES", ex.m("document", quoteRef, "reference", "key", quoteRef, "doc_type", "quote"));
        }
        for (Map<String, Object> it : items) {
            Matcher code = PRODUCT_CODE.matcher((String) it.get("desc"));
            if (code.find()) {
                ex.fact(doc, "LISTS_PRODUCT", ex.m("product", (String) it.get("desc"), "line_item", "code", code.group(1)));
            }
        }
        return true;
    }

    private boolean drawing(Extraction ex, FileRow row, String text, Integer folderCompany) {
        String no = field(text, "Drawing No", "(DWG-\\d+)");
        if (no == null) {
            return false;
        }
        String title = field(text, "Title", "(.+)");
        Integer doc = document(ex, row, "drawing", no, title != null ? title : no, "revision", field(text, "Rev", "(.+)"));
        Integer customer = ex.m("company", orEmpty(field(text, "Customer", "(.+)")), "drawing_customer");
        ex.fact(doc, "ISSUED_TO", customer);
        String job = field(text, "Job", "(.+)");
        if (job != null) {
            ex.fact(ex.mc("project", job, "doc_job_field", 0.9, "company_mention", customer != null ? customer : folderCompany),
                    "HAS_DOCUMENT", doc);
        }
        String by = field(text, "Drawn By", "(.+)");
        if (by != null) {
            ex.fact(personWithOrg(ex, by, "drawn_by", owner(ex)), "AUTHORED", doc);
        }
        return true;
    }

    private boolean letter(Extraction ex, FileRow row, String text, Integer folderCompany) {
        List<String> lines = Text.lines(text);
        Integer dateI = null;
        Integer bodyI = null;
        boolean signed = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (dateI == null && i < 12 && l.matches(DATE)) {
                dateI = i;
            }
            if (bodyI == null && (l.startsWith("Re:") || l.startsWith("Dear "))) {
                bodyI = i;
            }
            signed |= SIGNOFF.matcher(l).matches();
        }
        if (dateI == null || bodyI == null || bodyI <= dateI || !signed) {
            return false;
        }
        String stem = stem(row.path);
        Matcher n = Pattern.compile("\\d+").matcher(stem);
        String key = stem.toLowerCase().startsWith("letter") && n.find() ? "LETTER-" + n.group() : null;
        String subject = lines.get(bodyI).startsWith("Re:") ? lines.get(bodyI).substring(3).trim() : null;
        Integer doc = document(ex, row, "letter", key, key != null ? key : stem, "date", lines.get(dateI), "subject", subject);
        List<String> block = lines.subList(dateI + 1, bodyI);
        if (!block.isEmpty()) {
            String address = join(block.subList(1, block.size()), ", ");
            ex.fact(doc, "ADDRESSED_TO", ex.m("company", block.get(0), "letter_recipient", "address", address.isEmpty() ? null : address));
        }
        String job = subject != null && subject.contains("·") ? subject.split("·", 2)[1].trim() : null;
        if (job == null) {
            Matcher m = Pattern.compile("(?:regarding|progress with|schedule for|concerning)\\s+([A-Z][\\w&\\- ]+?)[,.]").matcher(text);
            if (m.find()) {
                job = m.group(1);
            }
        }
        if (job != null) {
            ex.fact(ex.mc("project", job, "doc_job_field", 0.9, "company_mention", folderCompany), "HAS_DOCUMENT", doc);
        }
        String ownerFirstWord = Config.ownerName.split(" ")[0];
        for (int i = 0; i < lines.size(); i++) {
            if (SIGNOFF.matcher(lines.get(i)).matches() && i + 1 < lines.size()) {
                Integer org = i + 2 < lines.size() && lines.get(i + 2).contains(ownerFirstWord) ? owner(ex) : null;
                String jobTitle = i + 2 < lines.size() ? lines.get(i + 2).split(",")[0] : null;
                ex.fact(personWithOrg(ex, lines.get(i + 1), "signatory", org, "job_title", jobTitle), "AUTHORED", doc);
            }
        }
        return true;
    }

    private boolean contract(Extraction ex, FileRow row, String text, Integer folderCompany) {
        Matcher m = Pattern.compile("^Between (.+?) and (.+)$", Pattern.MULTILINE).matcher(text);
        if (!m.find()) {
            return false;
        }
        String kind = Text.lines(text).get(0);
        String stem = baseStem(stem(row.path));
        String key = "contract:" + (row.folderJob != null ? row.folderJob : row.path) + ":" + stem;
        Integer doc = document(ex, row, "contract", key, kind + " (" + stem.replace("Contract_", "").replace("_", " ") + ")");
        ex.fact(ex.m("company", m.group(1), "contract_party"), "PARTY_TO", doc);
        ex.fact(ex.m("company", m.group(2), "contract_party"), "PARTY_TO", doc);
        String job = field(text, "Relating to", "(.+)");
        if (job != null) {
            ex.fact(ex.mc("project", job, "doc_job_field", 0.9, "company_mention", folderCompany), "HAS_DOCUMENT", doc);
        }
        return true;
    }

    private boolean spec(Extraction ex, FileRow row, String text) {
        Matcher head = Pattern.compile("^([A-Z]{2,4}-\\d{2,4}) · (Technical Specification|Datasheet)", Pattern.MULTILINE).matcher(text);
        if (!head.find()) {
            return false;
        }
        Matcher no = Pattern.compile("Doc No:\\s*(\\S+)").matcher(text);
        Matcher model = Pattern.compile("Model:\\s*(\\S+)").matcher(text);
        String docType = head.group(2).equals("Datasheet") ? "datasheet" : "specification";
        String key = no.find() ? no.group(1) : null;
        String titleCode = head.group(1);
        Integer doc = document(ex, row, docType, key, titleCode + " " + head.group(2) + (key != null ? " (" + key + ")" : ""));
        ex.fact(doc, "DESCRIBES", ex.m("product", titleCode, "spec_title", "code", titleCode));
        if (model.find() && !model.group(1).equals(titleCode)) {
            ex.issue("conflict", "info", "titled " + titleCode + " but body says Model: " + model.group(1));
        }
        return true;
    }

    private boolean manual(Extraction ex, FileRow row, String text) {
        List<String> lines = Text.lines(text);
        if (lines.size() < 2 || !lines.get(1).contains("Manual")) {
            return false;
        }
        Matcher m = Pattern.compile("^([A-Z]{2,4}-\\d{2,4})(?:-(\\d+))?$").matcher(lines.get(0));
        if (!m.matches()) {
            return false;
        }
        Matcher d = Pattern.compile(DATE).matcher(text);
        Integer doc = document(ex, row, "manual", "manual:" + lines.get(0), lines.get(0) + " " + lines.get(1),
                "date", d.find() ? d.group() : null);
        ex.fact(doc, "DESCRIBES", ex.m("product", m.group(1), "manual_title", "code", m.group(1)));
        return true;
    }

    private boolean calibration(Extraction ex, FileRow row, String text) {
        String no = field(text, "Certificate No", "(CAL-\\d+)");
        if (no == null) {
            return false;
        }
        Integer doc = document(ex, row, "calibration_cert", no, no,
                "instrument", field(text, "Instrument", "(.+)"), "serial", field(text, "Serial No", "(.+)"),
                "date", field(text, "Calibration Date", "(.+)"), "result", field(text, "Result", "(.+)"));
        String tech = field(text, "Technician", "(.+)");
        if (tech != null) {
            ex.fact(personWithOrg(ex, tech, "technician", owner(ex)), "AUTHORED", doc);
        }
        return true;
    }

    private boolean certificate(Extraction ex, FileRow row, String text) {
        if (!text.contains("certify that") && !text.contains("certifies that")) {
            return false;
        }
        List<String> lines = Text.lines(text);
        int i = 0;
        while (i < lines.size() && !(lines.get(i).contains("certif") && lines.get(i).contains("that"))) {
            i++;
        }
        String subject = i + 1 < lines.size() ? lines.get(i + 1) : "";
        String iso = field(text, "Certificate No", "(ISO-\\d+)");
        if (iso != null) {
            Integer doc = document(ex, row, "iso_certificate", iso, lines.get(1) + " certificate (" + iso + ")",
                    "valid_until", field(text, "Valid until", "(.+)"));
            ex.fact(ex.m("company", subject, "certified_company"), "HOLDS", doc);
            ex.fact(doc, "ISSUED_BY", ex.m("company", orEmpty(field(text, "Certification Body", "(.+)")), "certification_body"));
            return true;
        }
        Matcher n = Pattern.compile("\\d+").matcher(stem(row.path));
        Matcher expiry = Pattern.compile("Expiry:\\s*(" + DATE + ")").matcher(text);
        Integer doc = document(ex, row, "training_certificate", n.find() ? "CERT-" + n.group() : null,
                titleCase(lines.get(0)) + " – " + subject, "expiry", expiry.find() ? expiry.group(1) : null);
        Integer holder = personWithOrg(ex, subject, "certificate_holder", "HR".equals(row.area) ? owner(ex) : null);
        ex.fact(holder, "HOLDS", doc);
        ex.fact(doc, "ISSUED_BY", ex.m("company", orEmpty(field(text, "Training Provider", "(.+)")), "training_provider"));
        return true;
    }

    private boolean report(Extraction ex, FileRow row, String text) {
        Matcher m = Pattern.compile("Date:\\s*(" + DATE + ")\\s+Author:\\s*(.+)").matcher(text);
        if (!m.find() || !text.contains("Internal Report")) {
            return false;
        }
        String stem = stem(row.path);
        Integer doc = document(ex, row, "report", "report:" + stem, stem.replace("_", " "), "date", m.group(1));
        ex.fact(personWithOrg(ex, m.group(2), "report_author", owner(ex)), "AUTHORED", doc);
        return true;
    }

    private boolean email(Extraction ex, FileRow row, String text, Integer folderCompany) {
        if (row.kind != FileKind.EML) {
            return false;
        }
        int split = text.indexOf("\n\n");
        String head = split >= 0 ? text.substring(0, split) : text;
        String body = split >= 0 ? text.substring(split + 2) : "";
        Map<String, String> hdr = new HashMap<String, String>();
        Matcher hm = Pattern.compile("^(From|To|Cc|Date|Subject): (.*)$", Pattern.MULTILINE).matcher(head);
        while (hm.find()) {
            hdr.put(hm.group(1), hm.group(2));
        }
        String subject = hdr.containsKey("Subject") ? hdr.get("Subject") : "";
        String stem = stem(row.path);
        Integer doc = document(ex, row, "email", "email:" + stem, subject.isEmpty() ? stem : subject,
                "date", hdr.get("Date"), "subject", subject);
        String[][] roles = {{"From", "email_from", "SENT"}, {"To", "email_to", "RECEIVED"}, {"Cc", "email_cc", "RECEIVED"}};
        for (String[] r : roles) {
            for (InternetAddress a : addresses(hdr.get(r[0]))) {
                String addr = a.getAddress();
                if (addr == null || addr.isEmpty()) {
                    continue;
                }
                Integer org = companyFromDomain(ex, addr);
                String name = a.getPersonal() != null ? a.getPersonal() : nameFromAddress(addr);
                ex.fact(personWithOrg(ex, name, r[1], org, "email", addr.toLowerCase()), r[2], doc);
            }
        }
        // project by title in the subject or body
        String title = null;
        String[] subjectPatterns = {"^(?:RE|Fwd|FW):\\s*(.+?)\\s+(?:—|-)\\s+schedule update", "^(?:RE|Fwd|FW):\\s*(.+?) site visit$"};
        for (String pat : subjectPatterns) {
            Matcher m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(subject);
            if (m.lookingAt()) {
                title = m.group(1);
            }
        }
        if (title == null) {
            Matcher m = Pattern.compile("(?:concerning|regarding|schedule for|relating to)\\s+([A-Z][\\w&\\- ]+?)[,.]").matcher(body);
            if (m.find()) {
                title = m.group(1);
            }
        }
        if (title != null) {
            ex.fact(ex.mc("project", title, "email_subject", 0.8, "company_mention", folderCompany), "HAS_DOCUMENT", doc);
        }
        refs(ex, subject + "\n" + body, null);
        return true;
    }

    private boolean vcard(Extraction ex, FileRow row, String text) {
        if (row.kind != FileKind.VCF) {
            return false;
        }
        String fn = field(text, "FN", "(.+)");
        String org = field(text, "ORG", "(.+)");
        String mail = field(text, "EMAIL", "(.+)");
        String id = (mail != null ? mail : fn != null ? fn : "").toLowerCase();
        Integer doc = document(ex, row, "contact_card", "vcard:" + id, "Contact card: " + fn);
        Integer orgMention = ex.m("company", orEmpty(org), "vcard_org");
        Integer p = personWithOrg(ex, orEmpty(fn), "vcard", orgMention,
                "email", mail != null ? mail.toLowerCase() : null, "phone", field(text, "TEL", "(.+)"));
        if (mail != null) {
            ex.fact(p, "WORKS_FOR", companyFromDomain(ex, mail));
        }
        ex.fact(doc, "DESCRIBES", p);
        return true;
    }

    private boolean calendar(Extraction ex, FileRow row, String text, Integer folderCompany) {
        if (row.kind != FileKind.ICS) {
            return false;
        }
        String summary = orEmpty(field(text, "SUMMARY", "(.+)"));
        String uid = field(text, "UID", "(.+)");
        Integer doc = document(ex, row, "meeting", "event:" + (uid != null ? uid : stem(row.path)), summary,
                "date", field(text, "DTSTART", "(.+)"));
        Matcher m = Pattern.compile("^Site visit\\s+\\W\\s+(.+?)\\s+\\((.+)\\)$").matcher(summary);
        if (m.matches()) {
            Integer company = ex.m("company", m.group(2), "calendar_summary");
            ex.fact(doc, "ADDRESSED_TO", company);
            ex.fact(ex.mc("project", m.group(1), "calendar_summary", 0.9, "company_mention", company != null ? company : folderCompany),
                    "HAS_DOCUMENT", doc);
        }
        return true;
    }

    private boolean meetingNotes(Extraction ex, FileRow row, String text) {
        String attendees = field(text, "Attendees", "(.+)");
        if (attendees == null) {
            return false;
        }
        Integer doc = document(ex, row, "meeting_notes", null, Text.lines(text).get(0));
        for (String name : attendees.split(",|\\band\\b")) {
            ex.fact(personWithOrg(ex, name, "attendee", null), "ATTENDED", doc);
        }
        return true;
    }

    private boolean serviceReport(Extraction ex, FileRow row, String text) {
        Matcher m = Pattern.compile("Prepared by (.+?) on (" + DATE + ")").matcher(text);
        if (!m.find()) {
            return false;
        }
        Integer doc = document(ex, row, "report", null, Text.lines(text).get(0), "date", m.group(2));
        ex.fact(personWithOrg(ex, m.group(1), "report_author", owner(ex)), "AUTHORED", doc);
        return true;
    }

    /** Price-list exports and item spreadsheets. */
    private boolean itemList(Extraction ex, FileRow row, String text) {
        List<String> lines = Text.lines(text);
        if (lines.isEmpty()) {
            return false;
        }
        String first = lines.get(0).split("\\s*[|,]\\s*")[0].toLowerCase();
        if (!first.equals("item") && !first.equals("description")) {
            return false;
        }
        String type = row.path.toLowerCase().contains("price") ? "price_list" : "item_list";
        Integer doc = document(ex, row, type, null, stem(row.path).replace("_", " "));
        for (String l : lines.subList(1, lines.size())) {
            String cell = l.split("\\s*[|,]\\s*")[0];
            Matcher m = PRODUCT_CODE.matcher(cell);
            if (m.find()) {
                ex.fact(doc, "LISTS_PRODUCT", ex.m("product", cell, "line_item", "code", m.group(1)));
            }
        }
        return true;
    }

    /** OCR'd app screenshots: tables whose rows carry a JOB code and a customer name. */
    private boolean screenshotTable(Extraction ex, FileRow row, String text) {
        List<String> lines = Text.lines(text);
        List<String[]> rows = new ArrayList<String[]>();
        List<String> header = new ArrayList<String>();
        for (String l : lines) {
            if (JOB_ID.matcher(l).find()) {
                String[] cells = l.split("\\|", -1);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].trim();
                }
                rows.add(cells);
            } else if (header.isEmpty() && l.contains("|")) {
                for (String h : l.split("\\|", -1)) {
                    header.add(h.toLowerCase());
                }
            }
        }
        if (rows.isEmpty()) {
            return false;
        }
        String title = Text.truncate("Screenshot: " + lines.get(0), 80);
        Integer doc = document(ex, row, "screenshot", null, title);
        for (String[] cells : rows) {
            int jobI = 0;
            while (!JOB_ID.matcher(cells[jobI]).find()) {
                jobI++;
            }
            Map<String, String> attrs = new HashMap<String, String>();
            for (int i = 0; i < header.size() && i < cells.length; i++) {
                if (i != jobI && !header.get(i).trim().isEmpty()) {
                    attrs.put(header.get(i).trim(), cells[i]);
                }
            }
            String customer = attrs.containsKey("customer") ? attrs.get("customer")
                    : jobI + 1 < cells.length ? cells[jobI + 1] : null;
            Integer company = ex.m("company", orEmpty(customer), "screenshot_row");
            Matcher jm = JOB_ID.matcher(cells[jobI]);
            jm.find();
            Integer pj = ex.mc("project", jm.group(), "screenshot_row", 0.9, "job_id", jm.group(),
                    "company_mention", company, "status", attrs.get("status"), "value", attrs.get("value"));
            ex.fact(company, "HAS_PROJECT", pj);
            ex.fact(pj, "HAS_DOCUMENT", doc);
        }
        return true;
    }

    // ================================================================ shared helpers

    /** Cross-references anywhere in the text: document numbers and job codes. */
    private void refs(Extraction ex, String text, String skipKey) {
        Matcher m = DOC_NO.matcher(text);
        while (m.find()) {
            String key = m.group();
            String prefix = m.group(1);
            if (key.equals(skipKey) || prefix.equals("SPEC") || prefix.equals("DS") || prefix.equals("ISO")) {
                continue;
            }
            ex.fact(ex.doc, "REFERENCES", ex.mc("document", key, "reference", 0.9, "key", key, "doc_type", PREFIX_TYPES.get(prefix)));
        }
        Matcher j = JOB_ID.matcher(text);
        while (j.find()) {
            ex.fact(ex.mc("project", j.group(), "reference", 0.9, "job_id", j.group()), "HAS_DOCUMENT", ex.doc);
        }
    }

    /**
     * "Label: value" at the start of a line, or mid-line with a colon: OCR often merges
     * side-by-side columns ("CV6 4LP Date: 12 Oct 2023").
     */
    static String field(String text, String label, String value) {
        Pattern p = Pattern.compile("(?:^\\s*" + label + "\\s*:?|(?<=\\s)" + label + "\\s*:)\\s*" + value + "\\s*$",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** PDF text sometimes puts each table cell on its own line; stitch 5-cell item rows back together. */
    static List<String> joinTableRows(List<String> lines) {
        Pattern money = Pattern.compile("^" + CUR + "[\\d,]+\\.\\d{2}$");
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < lines.size()) {
            if (i + 4 < lines.size() && lines.get(i + 1).matches("\\d+") && lines.get(i + 2).matches("[a-z]+")
                    && money.matcher(lines.get(i + 3)).matches() && money.matcher(lines.get(i + 4)).matches()) {
                out.add(join(lines.subList(i, i + 5), " "));
                i += 5;
                continue;
            }
            if (i + 1 < lines.size() && lines.get(i).matches("(Subtotal|Tax \\(\\d+%\\)|TOTAL):")
                    && money.matcher(lines.get(i + 1)).matches()) {
                out.add(lines.get(i) + " " + lines.get(i + 1));
                i += 2;
                continue;
            }
            out.add(lines.get(i));
            i++;
        }
        return out;
    }

    private Integer personWithOrg(Extraction ex, String name, String role, Integer org, Object... attrs) {
        String clean = cleanPerson(name);
        if (clean == null) {
            return null;
        }
        Object[] all = new Object[attrs.length + 2];
        all[0] = "org_mention";
        all[1] = org;
        System.arraycopy(attrs, 0, all, 2, attrs.length);
        Integer p = ex.m("person", clean, role, all);
        ex.fact(p, "WORKS_FOR", org);
        return p;
    }

    private Integer owner(Extraction ex) {
        return ex.mc("company", Config.ownerName, "implied_owner", 0.8);
    }

    private Integer companyFromDomain(Extraction ex, String addr) {
        String domain = addr.substring(addr.lastIndexOf('@') + 1).toLowerCase();
        return ex.mc("company", domain, "email_domain", 0.9, "domain", domain);
    }

    static String cleanPerson(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().replaceFirst("(?i)^(Mr|Mrs|Ms|Miss|Dr)\\.?\\s+", "").replaceFirst("[_\\s]+$", "");
        return n.matches("^[A-Z][A-Za-z'’-]*\\.?(?: [A-Z][A-Za-z'’-]*\\.?){1,3}$") ? n : null;
    }

    /** File name without folder and extension. */
    static String stem(String path) {
        String[] members = path.split(Pattern.quote(Ingestor.MEMBER_SEP));
        String name = members[members.length - 1];
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
    }

    /** 'QUO-5238 FINAL' / 'QUO-5238_v2' / 'Contract_X__2' / 'y (1)' -> version-free stem. */
    static String baseStem(String stem) {
        String s = stem.replaceFirst("\\s*\\(\\d+\\)$", "");
        s = s.replaceFirst("(__\\d+|[_ -]v\\d+|[_ -]?(FINAL|final|revised|copy|draft))$", "");
        return s.replaceAll("^[ _-]+|[ _-]+$", "");
    }

    /** INV-8002_Acme Corporation -> {"INV-8002", "invoice", "Acme Corporation"}; nulls when not a numbered document. */
    static String[] filenameDoc(String stem) {
        Matcher m = Pattern.compile("^(INV|QUO|PO|DN|DWG|CAL)-(\\d+)(?:[_ ](.*))?$").matcher(stem);
        if (!m.matches()) {
            return new String[3];
        }
        String rest = m.group(3) == null ? "" : m.group(3).trim();
        String company = null;
        if (!rest.isEmpty() && !Pattern.compile("^(Rev\\w+|Calibration|v\\d+|FINAL|revised.*)$", Pattern.CASE_INSENSITIVE).matcher(rest).matches()) {
            company = rest;
        }
        return new String[] {m.group(1) + "-" + m.group(2), PREFIX_TYPES.get(m.group(1)), company};
    }

    private static List<InternetAddress> addresses(String header) {
        List<InternetAddress> out = new ArrayList<InternetAddress>();
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
    private static String nameFromAddress(String addr) {
        List<String> words = new ArrayList<String>();
        for (String w : addr.substring(0, addr.indexOf('@')).split("\\.")) {
            words.add(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase());
        }
        return join(words, " ");
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        return sb.toString();
    }

    private static double money(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ================================================================ stage runner

    /** Extracts every readable file and stores mentions, facts and issues. */
    public static Map<String, Integer> run(Connection conn) throws Exception {
        Extractor extractor = new Extractor();
        List<Map<String, Object>> rows = Db.query(conn, "SELECT * FROM files WHERE status != ? AND kind != ?",
                FileStatus.SKIPPED.value(), FileKind.ZIP.value());
        Map<Long, Long> docMentionOfFile = new HashMap<Long, Long>();
        List<FileRow> files = new ArrayList<FileRow>();
        int mentions = 0;
        int facts = 0;
        for (Map<String, Object> r : rows) {
            FileRow row = FileRow.of(r);
            files.add(row);
            Extraction ex = extractor.extractFile(row);
            List<Long> ids = new ArrayList<Long>();
            for (Extraction.Mention mt : ex.mentions) {
                ids.add(Db.insert(conn, "INSERT INTO mentions (file_id, etype, surface, role, attrs, confidence) VALUES (?,?,?,?,?,?)",
                        row.id, mt.etype, mt.surface, mt.role, "{}", mt.confidence));
            }
            // local mention indices in attrs (company_mention, org_mention) become database ids
            for (int i = 0; i < ex.mentions.size(); i++) {
                Map<String, Object> attrs = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> e : ex.mentions.get(i).attrs.entrySet()) {
                    Object v = e.getValue();
                    attrs.put(e.getKey(), e.getKey().endsWith("_mention") ? ids.get((Integer) v) : v);
                }
                Db.update(conn, "UPDATE mentions SET attrs=? WHERE id=?", Json.write(attrs), ids.get(i));
            }
            for (Extraction.Fact f : ex.facts) {
                Db.update(conn, "INSERT INTO facts (file_id, src, rel, dst) VALUES (?,?,?,?)", row.id, ids.get(f.src), f.rel, ids.get(f.dst));
            }
            for (String[] issue : ex.issues) {
                Db.update(conn, "INSERT INTO issues (kind, severity, detail, file_id) VALUES (?,?,?,?)", issue[0], issue[1], issue[2], row.id);
            }
            if (ex.doc != null) {
                docMentionOfFile.put(row.id, ids.get(ex.doc));
            }
            mentions += ids.size();
            facts += ex.facts.size();
        }
        // attachments and archive members point back at their container document
        for (FileRow row : files) {
            if (row.parentId != null && docMentionOfFile.containsKey(row.parentId) && docMentionOfFile.containsKey(row.id)) {
                Db.update(conn, "INSERT INTO facts (file_id, src, rel, dst) VALUES (?,?,?,?)",
                        row.id, docMentionOfFile.get(row.id), "ATTACHED_TO", docMentionOfFile.get(row.parentId));
            }
        }
        fileIssues(conn);
        Db.commit(conn);
        Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
        stats.put("files", rows.size());
        stats.put("mentions", mentions);
        stats.put("facts", facts);
        return stats;
    }

    private static void fileIssues(Connection conn) throws Exception {
        Map<String, FileKind> extKinds = new HashMap<String, FileKind>();   // extension -> the kind it promises
        extKinds.put(".pdf", FileKind.PDF);
        extKinds.put(".docx", FileKind.DOCX);
        extKinds.put(".xlsx", FileKind.XLSX);
        extKinds.put(".png", FileKind.PNG);
        extKinds.put(".jpg", FileKind.JPG);
        extKinds.put(".eml", FileKind.EML);
        for (Map<String, Object> r : Db.query(conn, "SELECT id, ext, kind, status, error, size FROM files WHERE status != ?",
                FileStatus.SKIPPED.value())) {
            String ext = (String) r.get("ext");
            FileKind kind = FileKind.fromValue((String) r.get("kind"));
            FileStatus status = FileStatus.fromValue((String) r.get("status"));
            if (ext != null && extKinds.containsKey(ext) && extKinds.get(ext) != kind) {
                Db.update(conn, "INSERT INTO issues (kind, severity, detail, file_id) VALUES ('mislabelled','info',?,?)",
                        "extension " + ext + " but content is " + kind.value(), r.get("id"));
            }
            if (status == FileStatus.CORRUPT || Db.id(r.get("size")) == 0) {
                Db.update(conn, "INSERT INTO issues (kind, severity, detail, file_id) VALUES ('unreadable','error',?,?)",
                        r.get("error") != null ? r.get("error") : "empty file (0 bytes)", r.get("id"));
            } else if (status == FileStatus.NEEDS_OCR) {
                Db.update(conn, "INSERT INTO issues (kind, severity, detail, file_id) VALUES ('needs_ocr','info',?,?)",
                        "image-only; content not read (enable an OCR backend)", r.get("id"));
            }
        }
    }
}
