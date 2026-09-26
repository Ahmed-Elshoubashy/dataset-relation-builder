package com.dubsof.graph.extract;

import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.MentionsDao;
import com.dubsof.graph.dao.row.FileRow;
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
 *   <li>document numbers and JOB codes cited anywhere in the text.</li>
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

    /** The mentions made from the folder a file sits in (indices in the Extraction, or null). */
    private static class FolderContext {
        final Integer customerMention;
        final Integer projectMention;

        FolderContext(Integer customerMention, Integer projectMention) {
            this.customerMention = customerMention;
            this.projectMention = projectMention;
        }
    }

    // ================================================================ one file

    /** Everything one file says: its mentions, the facts between them, and which mention is its own document. */
    public Extraction extractFile(FileRow row) {
        Extraction ex = new Extraction(row.id);
        String text = row.text == null ? "" : row.text;
        String stem = stem(row.path);

        FolderContext folder = addFolderContext(ex, row);
        if (row.status == FileStatus.OK) {
            runParsers(ex, row, text, folder);
        }
        if (ex.doc == null) {
            addDocumentFromFilename(ex, row, text, stem);
        }
        if (ex.doc == null) {
            return ex;   // a blank file no parser recognised: nothing to link
        }
        addFilenameHints(ex, stem);
        linkDocumentToFolder(ex, folder);

        // e-mails are skipped here: EmailParser already looks for references in the subject and body
        if (!text.isEmpty() && !"email".equals(ex.docMention().attrs.get("doc_type"))) {
            refs(ex, text, (String) ex.docMention().attrs.get("key"));
        }
        return ex;
    }

    /**
     * Mentions from the folder path "Customers/Acme Corporation/JOB-2023-0003 Shrink Wrap Retrofit/...":
     * company "Acme Corporation", project "Shrink Wrap Retrofit" (job_id JOB-2023-0003), and
     * the fact "company HAS_PROJECT project". The Ingestor already split the path into these parts.
     */
    private FolderContext addFolderContext(Extraction ex, FileRow row) {
        Integer customer = null;
        Integer project = null;
        if (row.folderCompany != null) {
            customer = ex.addMention(EntityType.COMPANY, row.folderCompany, MentionRole.FOLDER);
        }
        if (row.folderJob != null) {
            Matcher jobFolder = Ingestor.JOB_DIR.matcher(row.folderJob);   // "JOB-2023-0003 Shrink Wrap Retrofit"
            if (jobFolder.matches()) {
                String jobId = jobFolder.group(1);
                String title = jobFolder.group(2);
                project = ex.addMention(EntityType.PROJECT, title, MentionRole.FOLDER, "job_id", jobId, "company_mention", customer);
                ex.fact(customer, RelationType.HAS_PROJECT, project);
            }
        }
        return new FolderContext(customer, project);
    }

    /** Tries each parser in order; the first one that recognises the template adds the file's mentions and facts. */
    private void runParsers(Extraction ex, FileRow row, String text, FolderContext folder) {
        for (Parser parser : parsers) {
            if (parser.parse(ex, row, text, folder.customerMention, folder.projectMention)) {
                return;
            }
        }
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
    private void linkDocumentToFolder(Extraction ex, FolderContext folder) {
        ex.fact(folder.projectMention, RelationType.HAS_DOCUMENT, ex.doc);
        if (folder.projectMention == null) {
            ex.fact(ex.doc, RelationType.FILED_UNDER, folder.customerMention);
        }
    }

    // ================================================================ the whole stage

    /** Extracts every file (except skipped ones and zips, whose members are files of their own) and stores the result. */
    public static Map<String, Integer> run(Connection conn) throws Exception {
        return new Extractor().extractAll(conn);
    }

    private Map<String, Integer> extractAll(Connection conn) throws Exception {
        List<FileRow> files = filesDao.findExcept(conn, FileStatus.SKIPPED, FileKind.ZIP);
        Map<Long, Long> documentMentionOfFile = new HashMap<>();   // file id -> database id of its document mention
        int mentionCount = 0;
        int factCount = 0;

        for (FileRow file : files) {
            Extraction ex = extractFile(file);
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
