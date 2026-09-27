package com.dubsof.graph.extract;

import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.parsers.BusinessDocParser;
import com.dubsof.graph.extract.parsers.CalendarParser;
import com.dubsof.graph.extract.parsers.CalibrationParser;
import com.dubsof.graph.extract.parsers.CertificateParser;
import com.dubsof.graph.extract.parsers.ContractParser;
import com.dubsof.graph.extract.parsers.DrawingParser;
import com.dubsof.graph.extract.parsers.EmailParser;
import com.dubsof.graph.extract.parsers.FilenameDocument;
import com.dubsof.graph.extract.parsers.ItemListParser;
import com.dubsof.graph.extract.parsers.LlmParser;
import com.dubsof.graph.extract.parsers.LetterParser;
import com.dubsof.graph.extract.parsers.ManualParser;
import com.dubsof.graph.extract.parsers.MeetingNotesParser;
import com.dubsof.graph.extract.parsers.Parser;
import com.dubsof.graph.extract.parsers.ReportParser;
import com.dubsof.graph.extract.parsers.ScreenshotTableParser;
import com.dubsof.graph.extract.parsers.ServiceReportParser;
import com.dubsof.graph.extract.parsers.SpecParser;
import com.dubsof.graph.extract.parsers.VcardParser;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.ingest.Ingestor;
import com.dubsof.graph.pipeline.Progress;
import com.dubsof.graph.util.Text;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.PREFIX_TYPES;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.filenameDoc;
import static com.dubsof.graph.extract.parsers.ParserUtils.refs;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;

/**
 * Stage 3: turn each file into mentions (raw references to entities) and facts
 * (typed links between mentions of the same file).
 *
 * Nothing here decides identity: "Acme Corp." and "ACME Corp" are just two company
 * mentions. The Resolver clusters mentions into entities afterwards.
 *
 * For each file, in this order (the order decides the mention ids):
 * <ol>
 *   <li>the folder it sits in: customer company and JOB project;</li>
 *   <li>the first parser that recognises its template adds the file's document and what it says;</li>
 *   <li>no parser matched, or the file is unread: a document from the file name only;</li>
 *   <li>hints in the file name: company and product code;</li>
 *   <li>the document is linked to its folder project (or folder customer);</li>
 *   <li>document numbers and job ids cited anywhere in the text;</li>
 *   <li>the general extractor (LlmParser), for files no template recognised and for the free text of e-mails,
 *       letters and meeting notes: every company, person, project, document and product the text names.</li>
 * </ol>
 */
public class Extractor {

    /** Confidence of a company name read from a file name: often cut off ("Redwood Timber &amp; J"). */
    private static final double FILENAME_COMPANY_CONFIDENCE = 0.7;
    /** Confidence of a product code read from a file name. */
    private static final double FILENAME_PRODUCT_CONFIDENCE = 0.8;
    /** A file name starting with a product code: "GB-40_—_Datasheet_2", "HL-6200-2_manual". */
    private static final Pattern FILENAME_PRODUCT_CODE = Pattern.compile("^([A-Z]{2,4}-\\d{2,4})(?:-\\d+)?_");
    /** ...unless the code is really a document number: "INV-8002_Acme Corporation". */
    private static final Pattern FILENAME_DOCUMENT_NUMBER = Pattern.compile("^(INV|QUO|PO|DN|DWG|CAL)-");

    private final Dataset dataset;
    private final FilesDao filesDao = new FilesDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final FactsDao factsDao = new FactsDao();

    /** One parser per document template, tried in this order; the first that recognises the file wins. */
    private final Parser[] parsers = {
        new EmailParser(),
        new VcardParser(),
        new CalendarParser(),
        new BusinessDocParser(),
        new DrawingParser(),
        new CalibrationParser(),
        new SpecParser(),
        new ManualParser(),
        new CertificateParser(),
        new LetterParser(),
        new ContractParser(),
        new ReportParser(),
        new ServiceReportParser(),
        new MeetingNotesParser(),
        new ItemListParser(),
        new ScreenshotTableParser(),
    };

    /** Tried after the templates: reads free text with Claude, or with rules offline. */
    private final LlmParser freeText;

    public Extractor(Dataset dataset, LlmParser freeText) {
        this.dataset = dataset;
        this.freeText = freeText;
    }

    /** Offline: the general extractor uses its rules. */
    public Extractor(Dataset dataset) {
        this(dataset, new LlmParser());
    }

    /** An extractor for a dataset with no known owner, offline. */
    public Extractor() {
        this(Dataset.unknown());
    }

    /** The mentions made from the folder a file sits in (indices in the Extraction, or null). */
    private static class FolderMentions {
        final Integer customerMention;
        final Integer projectMention;

        FolderMentions(Integer customerMention, Integer projectMention) {
            this.customerMention = customerMention;
            this.projectMention = projectMention;
        }
    }

    /** One file after the template steps, waiting for the general extractor (which may ask Claude about many files at once). */
    private static class TemplateResult {
        final Extraction ex;
        final FolderMentions folder;
        /** The text for the general extractor, or null when it has nothing to add. */
        final String freeText;

        TemplateResult(Extraction ex, FolderMentions folder, String freeText) {
            this.ex = ex;
            this.folder = folder;
            this.freeText = freeText;
        }
    }

    // ================================================================ one file

    /** Everything one file says: its mentions, the facts between them, and which mention is its own document. */
    public Extraction extractFile(FileRow row) {
        TemplateResult result = extractWithTemplates(row);
        addFreeText(result, row);
        return result.ex;
    }

    /** Steps 1-6: the folder, the templates, the file name and the references. */
    private TemplateResult extractWithTemplates(FileRow row) {
        Extraction ex = new Extraction(row.id, dataset);
        String text = row.text == null ? "" : row.text;
        String stem = stem(row.path);

        FolderMentions folder = addFolderContext(ex, row);
        Parser recognisedBy = null;
        if (row.status == FileStatus.OK) {
            recognisedBy = runParsers(ex, row, text, folder);
        }
        if (ex.doc == null) {
            addDocumentFromFilename(ex, row, text, stem);
        }
        if (ex.doc == null) {
            return new TemplateResult(ex, folder, null);   // a blank file no parser recognised: nothing to link
        }
        addFilenameHints(ex, stem);
        linkDocumentToFolder(ex, folder);

        // e-mails are skipped here: EmailParser already looks for references in the subject and body
        if (!text.isEmpty() && !"email".equals(ex.docMention().attrs.get("doc_type"))) {
            refs(ex, text, (String) ex.docMention().attrs.get("key"));
        }
        return new TemplateResult(ex, folder, freeTextOf(row, text, recognisedBy));
    }

    /**
     * What the general extractor should read: the whole text of a file no template recognised, the body of
     * an e-mail (its headers are already read), the whole of a letter or meeting notes; null for the rest,
     * whose template fields already cover what they say.
     */
    private static String freeTextOf(FileRow row, String text, Parser recognisedBy) {
        if (row.status != FileStatus.OK || Text.isBlank(text)) {
            return null;
        }
        if (recognisedBy == null || recognisedBy instanceof LetterParser || recognisedBy instanceof MeetingNotesParser) {
            return text;
        }
        if (recognisedBy instanceof EmailParser) {
            int bodyStart = text.indexOf("\n\n");
            return bodyStart < 0 ? null : text.substring(bodyStart + 2);
        }
        return null;
    }

    /** Step 7: the general extractor. */
    private void addFreeText(TemplateResult result, FileRow row) {
        if (result.freeText != null) {
            freeText.parse(result.ex, row, result.freeText, result.folder.customerMention, result.folder.projectMention);
        }
    }

    /**
     * Mentions from the folder path, read with the profile's folder layout, e.g. "Clients/Acme/P-12 Line Upgrade/...":
     * company "Acme", project "Line Upgrade" (job_id P-12), and
     * the fact "company HAS_PROJECT project". The Ingestor already split the path into these parts.
     */
    private FolderMentions addFolderContext(Extraction ex, FileRow row) {
        Integer customer = null;
        Integer project = null;
        if (row.folderCompany != null) {
            customer = ex.addMention(EntityType.COMPANY, row.folderCompany, MentionRole.FOLDER);
        }
        if (row.folderJobId != null) {
            String title = row.folderJobTitle != null ? row.folderJobTitle : row.folderJobId;
            project = ex.addMention(EntityType.PROJECT, title, MentionRole.FOLDER, "job_id", row.folderJobId,
                    "company_mention", customer);
            ex.fact(customer, RelationType.HAS_PROJECT, project);
        }
        return new FolderMentions(customer, project);
    }

    /**
     * Tries each parser in order; the first one that recognises the template adds the file's mentions and facts.
     *
     * @return that parser, or null when none recognised the file
     */
    private Parser runParsers(Extraction ex, FileRow row, String text, FolderMentions folder) {
        for (Parser parser : parsers) {
            if (parser.parse(ex, row, text, folder.customerMention, folder.projectMention)) {
                return parser;
            }
        }
        return null;
    }

    /**
     * The file was not read (needs OCR, corrupt, empty) or no parser recognised it: its document comes from
     * the file name ("INV-8002_Acme Corporation" -> document INV-8002, an invoice). Skipped for a readable file
     * with no document number in its name and no text, which has nothing to say.
     */
    private void addDocumentFromFilename(Extraction ex, FileRow row, String text, String stem) {
        FilenameDocument fromName = filenameDoc(stem);
        if (row.status != FileStatus.OK || fromName.number != null || !Text.isBlank(text)) {
            document(ex, row, fromName.docType, fromName.number, fromName.number != null ? fromName.number : stem,
                    "unread", row.status == FileStatus.OK ? null : Boolean.TRUE);
        }
    }

    /**
     * Hints in the file name. A company after the number ("INV-8002_Acme Corporation") is often cut off;
     * for business documents it is the customer the document is ISSUED_TO. A leading product code
     * ("GB-40_—_Datasheet_2") is the product the document DESCRIBES.
     */
    private void addFilenameHints(Extraction ex, String stem) {
        String filenameCompany = filenameDoc(stem).company;
        if (filenameCompany != null) {
            Integer company = ex.addMentionWithConfidence(EntityType.COMPANY, filenameCompany, MentionRole.FILENAME,
                    FILENAME_COMPANY_CONFIDENCE, "truncated", Boolean.TRUE);
            boolean isBusinessDocument = PREFIX_TYPES.containsValue(ex.docMention().attrs.get("doc_type"));
            if (isBusinessDocument) {
                ex.fact(ex.doc, RelationType.ISSUED_TO, company);
            }
        }

        Matcher productCode = FILENAME_PRODUCT_CODE.matcher(stem);
        if (productCode.lookingAt() && !FILENAME_DOCUMENT_NUMBER.matcher(stem).lookingAt()) {
            String code = productCode.group(1);
            Integer product = ex.addMentionWithConfidence(EntityType.PRODUCT, code, MentionRole.FILENAME,
                    FILENAME_PRODUCT_CONFIDENCE, "code", code);
            ex.fact(ex.doc, RelationType.DESCRIBES, product);
        }
    }

    /** The folder's project HAS_DOCUMENT this document; a file with no JOB folder is FILED_UNDER its customer instead. */
    private void linkDocumentToFolder(Extraction ex, FolderMentions folder) {
        ex.fact(folder.projectMention, RelationType.HAS_DOCUMENT, ex.doc);
        if (folder.projectMention == null) {
            ex.fact(ex.doc, RelationType.FILED_UNDER, folder.customerMention);
        }
    }

    // ================================================================ the whole stage

    /** Extracts every file (except skipped ones and zips, whose members are files of their own) and stores the result. */
    /**
     * @param llmApiKey the key the general extractor uses to ask Claude; null reads free text with rules, offline
     */
    public static Map<String, Integer> run(Connection conn, Dataset dataset, String llmApiKey) throws Exception {
        return run(conn, dataset, llmApiKey, (step, stage, detail) -> { });
    }

    /** As above, and reports failed Claude calls (a bad key, a rate limit) to the analysis log. */
    public static Map<String, Integer> run(Connection conn, Dataset dataset, String llmApiKey, Progress progress) throws Exception {
        try (LlmParser freeText = new LlmParser(llmApiKey)) {
            Map<String, Integer> summary = new Extractor(dataset, freeText).extractAll(conn);
            String problem = freeText.claudeProblem();
            if (problem != null) {
                progress.update(3, "extract", problem);
            }
            return summary;
        }
    }

    private Map<String, Integer> extractAll(Connection conn) throws Exception {
        List<FileRow> files = filesDao.findExcept(conn, FileStatus.SKIPPED, FileKind.ZIP);
        Map<Long, Long> documentMentionOfFile = new HashMap<>();   // file id -> database id of its document mention
        int mentionCount = 0;
        int factCount = 0;

        // Templates first, for every file; then the general extractor, so Claude gets all its files at once.
        List<TemplateResult> results = new ArrayList<>();
        List<FileRow> freeTextFiles = new ArrayList<>();
        List<String> freeTexts = new ArrayList<>();
        for (FileRow file : files) {
            TemplateResult result = extractWithTemplates(file);
            results.add(result);
            if (result.freeText != null) {
                freeTextFiles.add(file);
                freeTexts.add(result.freeText);
            }
        }
        freeText.prepare(freeTextFiles, freeTexts);

        for (int i = 0; i < files.size(); i++) {
            FileRow file = files.get(i);
            addFreeText(results.get(i), file);
            Extraction ex = results.get(i).ex;
            List<Long> mentionIds = saveExtraction(conn, file, ex);
            if (ex.doc != null) {
                documentMentionOfFile.put(file.id, mentionIds.get(ex.doc));
            }
            mentionCount += mentionIds.size();
            factCount += ex.facts.size();
        }
        linkAttachmentsToContainers(conn, files, documentMentionOfFile);
        Db.commit(conn);

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("files", files.size());
        summary.put("mentions", mentionCount);
        summary.put("facts", factCount);
        summary.put("free_text_files", freeTextFiles.size());
        summary.putAll(freeText.claudeSummary());
        return summary;
    }

    /**
     * Writes one file's mentions and facts. Inside an Extraction, mentions refer to each other by their
     * index in the list (in facts, and in attrs like company_mention); here those indices become database ids.
     *
     * @return the database id of each mention, by its index in the Extraction
     */
    private List<Long> saveExtraction(Connection conn, FileRow file, Extraction ex) throws Exception {
        List<Long> mentionIds = new ArrayList<>();
        for (Extraction.Mention mention : ex.mentions) {
            mentionIds.add(mentionsDao.insert(conn, file.id, mention.etype, mention.surface, mention.role, mention.confidence));
        }

        // attrs ending in "_mention" hold a mention index: replace it with that mention's database id
        for (int i = 0; i < ex.mentions.size(); i++) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> attribute : ex.mentions.get(i).attrs.entrySet()) {
                String key = attribute.getKey();
                Object value = attribute.getValue();
                attributes.put(key, key.endsWith("_mention") ? mentionIds.get((Integer) value) : value);
            }
            mentionsDao.updateAttrs(conn, mentionIds.get(i), attributes);
        }

        for (Extraction.Fact fact : ex.facts) {
            factsDao.insert(conn, file.id, mentionIds.get(fact.src), fact.rel, mentionIds.get(fact.dst));
        }
        return mentionIds;
    }

    /**
     * An e-mail attachment or zip member is ATTACHED_TO the document of the file it came from.
     * Done after all files, because it needs both files' document mentions.
     */
    private void linkAttachmentsToContainers(Connection conn, List<FileRow> files, Map<Long, Long> documentMentionOfFile)
            throws Exception {
        for (FileRow file : files) {
            boolean bothHaveDocuments = file.parentId != null
                    && documentMentionOfFile.containsKey(file.parentId) && documentMentionOfFile.containsKey(file.id);
            if (bothHaveDocuments) {
                factsDao.insert(conn, file.id, documentMentionOfFile.get(file.id), RelationType.ATTACHED_TO,
                        documentMentionOfFile.get(file.parentId));
            }
        }
    }
}
