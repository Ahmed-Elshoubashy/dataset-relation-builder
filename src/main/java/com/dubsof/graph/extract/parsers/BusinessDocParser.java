package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.DATE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;
import static com.dubsof.graph.extract.parsers.ParserUtils.join;

/** Invoices, quotations, purchase orders and delivery notes: number, bill-to company, line items and totals. */
public class BusinessDocParser implements Parser {

    /** An amount with its currency sign or code, before or after it (see Money). */
    private static final String MONEY = Money.BEFORE + "(" + Money.AMOUNT + ")" + Money.AFTER;
    private static final Pattern LINE_ITEM = Pattern.compile("^(?<desc>.+?)\\s+(?<qty>\\d+)\\s+(?<unit>[a-z]+)\\s+"
            + Money.BEFORE + "(?<price>" + Money.AMOUNT + ")" + Money.AFTER + "\\s+"
            + Money.BEFORE + "(?<total>" + Money.AMOUNT + ")" + Money.AFTER + "\\s*$");
    private static final Pattern TOTAL_LINE = Pattern.compile("^(Subtotal|Tax \\(\\d+%\\)|TOTAL)\\s*:?\\s*" + MONEY + "$", Pattern.CASE_INSENSITIVE);
    /** Header line -> document type. */
    private static final Map<String, String> DOC_TYPES = new LinkedHashMap<String, String>();

    static {
        DOC_TYPES.put("INVOICE", "invoice");
        DOC_TYPES.put("QUOTATION", "quote");
        DOC_TYPES.put("PURCHASE ORDER", "purchase_order");
        DOC_TYPES.put("DELIVERY NOTE", "delivery_note");
    }

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        List<String> rawLines = Text.lines(text);
        String header = null;
        for (String l : rawLines.subList(0, Math.min(15, rawLines.size()))) {
            for (Map.Entry<String, String> t : DOC_TYPES.entrySet()) {
                if (header == null && (l.equals(t.getKey()) || l.endsWith(" " + t.getKey()))) {
                    header = t.getValue();
                }
            }
        }
        // "No: INV-8034", "Invoice No: HR-1042": any code under a heading; without one, only a number whose
        // prefix is one of these document types ("Drawing No: DWG-4889" is left to the drawing parser)
        String number = field(text, "No", "([A-Z]{2,5}-\\d+)");
        if (number != null && header == null && !DOC_TYPES.containsValue(ex.documentNumbers.typeOf(number))) {
            number = null;
        }
        if (header == null && number == null) {
            return false;
        }
        FilenameDocument fromName = ex.documentNumbers.fromFilename(stem(row.path));
        String key = number != null ? number : fromName.number;
        String docType = header != null ? header : number != null ? ex.documentNumbers.typeOf(number) : fromName.docType;
        String date = field(text, "Date", "(" + DATE + ")");
        String job = field(text, "Job", "(.+)");
        String quoteRef = field(text, "Quote Ref", "([A-Z]{2,5}-\\d+)");

        List<String> cleaned = new ArrayList<String>();
        for (String l : rawLines) {
            cleaned.add(l.replace(" | ", " "));
        }
        List<String> lines = joinTableRows(cleaned);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Map<String, Double> totals = new HashMap<String, Double>();
        String currency = null;   // from the total line, else from the first line item
        for (String l : lines) {
            Matcher m = LINE_ITEM.matcher(l);
            Matcher t = TOTAL_LINE.matcher(l);
            if (m.matches() && !l.toLowerCase().startsWith("description")) {
                Map<String, Object> it = new LinkedHashMap<String, Object>();
                it.put("desc", m.group("desc"));
                it.put("qty", Integer.parseInt(m.group("qty")));
                it.put("unit", m.group("unit"));
                it.put("price", Money.parse(m.group("price")));
                it.put("total", Money.parse(m.group("total")));
                items.add(it);
                if (currency == null) {
                    currency = Money.currency(l);
                }
            } else if (t.matches()) {
                String label = t.group(1).split(" ")[0].toLowerCase();
                totals.put(label, Money.parse(t.group(2)));
                if (label.equals("total") && Money.currency(l) != null) {
                    currency = Money.currency(l);
                }
            }
        }

        Integer doc = document(ex, row, docType, key, key, "date", date, "job_title", job,
                "total", totals.get("total"), "subtotal", totals.get("subtotal"), "currency", currency,
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
                    billCompany = ex.addMention(EntityType.COMPANY, block.get(0), MentionRole.BILL_TO, "address", address.isEmpty() ? null : address);
                }
                break;
            }
        }
        ex.fact(doc, RelationType.ISSUED_TO, billCompany);
        String attn = field(text, "Attn", "(.+)");
        if (attn != null) {
            ex.fact(doc, RelationType.ATTENTION_OF, personWithOrg(ex, attn, MentionRole.ATTN, billCompany));
        }
        if (job != null) {
            Integer pj = ex.addMentionWithConfidence(EntityType.PROJECT, job, MentionRole.DOC_JOB_FIELD, 0.9,
                    "company_mention", billCompany != null ? billCompany : folderCompany);
            ex.fact(pj, RelationType.HAS_DOCUMENT, doc);
        }
        if (quoteRef != null) {
            ex.fact(doc, RelationType.REFERENCES, ex.addMention(EntityType.DOCUMENT, quoteRef, MentionRole.REFERENCE, "key", quoteRef, "doc_type", "quote"));
        }
        for (Map<String, Object> it : items) {
            String code = ex.documentNumbers.productCode((String) it.get("desc"));
            if (code != null) {
                ex.fact(doc, RelationType.LISTS_PRODUCT, ex.addMention(EntityType.PRODUCT, (String) it.get("desc"), MentionRole.LINE_ITEM, "code", code));
            }
        }
        return true;
    }

    /** PDF text sometimes puts each table cell on its own line; stitch 5-cell item rows back together. */
    private static List<String> joinTableRows(List<String> lines) {
        Pattern money = Pattern.compile("^" + Money.BEFORE + Money.AMOUNT + Money.AFTER + "$");
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

}
