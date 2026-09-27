package com.dubsof.graph.extract.parsers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows what a document number looks like ("INV-8034", "HR-1042", "SO-88341") and which
 * type of document it is. A number is recognised in three ways:
 * <ul>
 *   <li>after a label, in any format: "Invoice No: HR-1042", "PO Number: PO-3038", "Ref: QUO-5236";</li>
 *   <li>without a label, only when its prefix is known: from the defaults below, the profile's
 *       {@code documentPrefixes}, or a labelled number seen anywhere in the dataset ({@link #learn}).
 *       An unknown "PK-10" may just as well be a part number;</li>
 *   <li>at the start of a file name, with a known prefix: "INV-8002_Acme Corporation".</li>
 * </ul>
 * One instance per analysis: it learns the dataset's prefixes before the parsers run.
 */
public final class DocumentNumbers {

    /** Prefixes known without a profile: the usual abbreviations of business documents. */
    public static final Map<String, String> DEFAULT_PREFIXES = new LinkedHashMap<>();

    static {
        DEFAULT_PREFIXES.put("INV", "invoice");
        DEFAULT_PREFIXES.put("QUO", "quote");
        DEFAULT_PREFIXES.put("PO", "purchase_order");
        DEFAULT_PREFIXES.put("DN", "delivery_note");
        DEFAULT_PREFIXES.put("DWG", "drawing");
        DEFAULT_PREFIXES.put("CAL", "calibration_cert");
        DEFAULT_PREFIXES.put("SPEC", "specification");
        DEFAULT_PREFIXES.put("DS", "datasheet");
        DEFAULT_PREFIXES.put("ISO", "iso_certificate");
    }

    /**
     * Known prefixes that are not a reference when they stand alone in text: "ISO-9001" names the standard,
     * not a certificate, and SPEC- / DS- codes name a product's sheet.
     */
    private static final Set<String> NOT_REFERENCES_ALONE = new HashSet<>(Arrays.asList("SPEC", "DS", "ISO"));
    /** Codes that are neither documents nor products: job numbers and serial numbers. */
    private static final Set<String> NEITHER_DOCUMENT_NOR_PRODUCT = new HashSet<>(Arrays.asList("JOB", "SN"));

    /** Label -> document type (null: the label does not say, e.g. "Ref"). Longest labels first. */
    private static final Map<String, String> LABEL_TYPES = new LinkedHashMap<>();

    static {
        LABEL_TYPES.put("purchase order", "purchase_order");
        LABEL_TYPES.put("delivery note", "delivery_note");
        LABEL_TYPES.put("credit note", "credit_note");
        LABEL_TYPES.put("sales order", "order");
        LABEL_TYPES.put("quotation", "quote");
        LABEL_TYPES.put("reference", null);
        LABEL_TYPES.put("invoice", "invoice");
        LABEL_TYPES.put("contract", "contract");
        LABEL_TYPES.put("drawing", "drawing");
        LABEL_TYPES.put("quote", "quote");
        LABEL_TYPES.put("order", "order");
        LABEL_TYPES.put("ref", null);
        LABEL_TYPES.put("po", "purchase_order");
        LABEL_TYPES.put("so", "order");
    }

    /** "Invoice No: HR-1042", "PO Number PO-3038", "Ref: QUO-5236", "invoice HR-1041": a label, then a code. */
    private static final Pattern LABELLED = Pattern.compile(
            "(?i:\\b(" + String.join("|", LABEL_TYPES.keySet()).replace(" ", "\\s+") + ")\\b)"
                    + "\\s*(?i:no\\.?|number|nr\\.?|#)?\\s*[:#]?\\s*\\b([A-Z]{2,5})-(\\d{2,}(?:-\\d+)*)\\b");
    /** A code with a prefix, alone in text: "INV-8034". Only counts when the prefix is known. */
    private static final Pattern CODE = Pattern.compile("\\b([A-Z]{2,5})-(\\d{3,6})\\b");
    /** "INV-8002_Acme Corporation", "HR-1042": a number at the start of a file name, and what follows it. */
    private static final Pattern FILENAME = Pattern.compile("^([A-Z]{2,5})-(\\d+)(?:[_ ](.*))?$");
    /** Words after the number in a file name that are not a company: "Rev B", "Calibration", "v2", "FINAL". */
    private static final Pattern NOT_A_COMPANY = Pattern.compile("^(Rev\\w+|Calibration|v\\d+|FINAL|revised.*)$", Pattern.CASE_INSENSITIVE);
    /** Equipment/part codes like HL-6200, VFD-15, SM-750. */
    private static final Pattern PRODUCT_CODE = Pattern.compile("\\b([A-Z]{2,4})-\\d{2,4}\\b");

    /** A document number found in text, and its type (null when neither label nor prefix says). */
    public static class Found {
        public final String number;
        public final String docType;

        Found(String number, String docType) {
            this.number = number;
            this.docType = docType;
        }
    }

    /** Prefix -> document type (null when only known to be a document). */
    private final Map<String, String> prefixes = new LinkedHashMap<>(DEFAULT_PREFIXES);
    /** The dataset's job ids ("P-2041"): projects, never documents or products. Null: none. */
    private final Pattern jobIdPattern;

    /**
     * @param profilePrefixes the profile's {@code documentPrefixes} ("HR" -> "invoice"), added to the defaults
     * @param jobIdPattern    the profile's job ids, or null
     */
    public DocumentNumbers(Map<String, String> profilePrefixes, Pattern jobIdPattern) {
        this.prefixes.putAll(profilePrefixes);
        this.jobIdPattern = jobIdPattern;
    }

    /** Defaults only: no profile, nothing learned. */
    public DocumentNumbers() {
        this(new LinkedHashMap<>(), null);
    }

    /**
     * Learns the prefixes of numbers whose label names a type ("Invoice No: HR-1042" -> HR is an invoice),
     * so the same prefix is recognised without a label elsewhere. A bare "Ref:" teaches nothing.
     */
    public void learn(String text) {
        for (Found found : labelled(text)) {
            String prefix = prefixOf(found.number);
            if (found.docType != null && !prefixes.containsKey(prefix) && !NEITHER_DOCUMENT_NOR_PRODUCT.contains(prefix)) {
                prefixes.put(prefix, found.docType);
            }
        }
    }

    /** Numbers after a label, in any format; the label's type, else the prefix's. */
    public List<Found> labelled(String text) {
        List<Found> found = new ArrayList<>();
        for (Matcher m = LABELLED.matcher(text); m.find(); ) {
            String number = m.group(2) + "-" + m.group(3);
            if (isJobId(number)) {
                continue;
            }
            String labelType = LABEL_TYPES.get(m.group(1).toLowerCase().replaceAll("\\s+", " "));
            found.add(new Found(number, labelType != null ? labelType : prefixes.get(m.group(2))));
        }
        return found;
    }

    /**
     * The documents a text refers to: labelled numbers, and codes with a known prefix
     * (except those that name a standard or a sheet when they stand alone, see NOT_REFERENCES_ALONE).
     */
    public List<Found> references(String text) {
        Map<String, Found> byNumber = new LinkedHashMap<>();
        for (Found found : labelled(text)) {
            byNumber.put(found.number, found);
        }
        for (Matcher m = CODE.matcher(text); m.find(); ) {
            String prefix = m.group(1);
            if (prefixes.containsKey(prefix) && !NOT_REFERENCES_ALONE.contains(prefix) && !byNumber.containsKey(m.group())
                    && !isJobId(m.group())) {
                byNumber.put(m.group(), new Found(m.group(), prefixes.get(prefix)));
            }
        }
        return new ArrayList<>(byNumber.values());
    }

    /** "INV-8002_Acme Corporation" -> number INV-8002, invoice, company "Acme Corporation"; all null without a known prefix. */
    public FilenameDocument fromFilename(String stem) {
        Matcher m = FILENAME.matcher(stem);
        if (!m.matches() || !prefixes.containsKey(m.group(1)) || NOT_REFERENCES_ALONE.contains(m.group(1))) {
            return new FilenameDocument(null, null, null);
        }
        String rest = m.group(3) == null ? "" : m.group(3).trim();
        String company = rest.isEmpty() || NOT_A_COMPANY.matcher(rest).matches() ? null : rest;
        return new FilenameDocument(m.group(1) + "-" + m.group(2), prefixes.get(m.group(1)), company);
    }

    /** The document type of a number's prefix ("HR-1042" -> "invoice" once learned), or null. */
    public String typeOf(String number) {
        return prefixes.get(prefixOf(number));
    }

    /** True for a type that business documents have (invoice, quote, ...), as opposed to "email", "letter", ... */
    public boolean isNumberedType(Object docType) {
        return docType != null && prefixes.containsValue(docType);
    }

    /** The first product code in a text ("Pick Cell PK-10" -> "PK-10"), skipping document numbers and job or serial numbers. */
    public String productCode(String text) {
        for (Matcher m = PRODUCT_CODE.matcher(text); m.find(); ) {
            String prefix = m.group(1);
            if (!prefixes.containsKey(prefix) && !NEITHER_DOCUMENT_NOR_PRODUCT.contains(prefix) && !isJobId(m.group())) {
                return m.group();
            }
        }
        return null;
    }

    private boolean isJobId(String code) {
        return jobIdPattern != null && jobIdPattern.matcher(code).lookingAt();
    }

    private static String prefixOf(String number) {
        int dash = number.indexOf('-');
        return dash < 0 ? number : number.substring(0, dash);
    }
}
