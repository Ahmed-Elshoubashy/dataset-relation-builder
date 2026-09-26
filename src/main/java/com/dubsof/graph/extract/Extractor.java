package com.dubsof.graph.extract;

import com.dubsof.graph.dao.FactsDao;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.IssuesDao;
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
 */
public class Extractor {

    private final FilesDao filesDao = new FilesDao();
    private final MentionsDao mentionsDao = new MentionsDao();
    private final FactsDao factsDao = new FactsDao();
    private final IssuesDao issuesDao = new IssuesDao();

    /** Tried in this order; the first that recognises the file wins. */
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
                // FIXME, can we get something like getParser ??
                if (parser.parse(ex, row, text, folderCompany, project)) {
                    break;
                }
            }
        }
        
        if (ex.doc == null) {
            // Unread (needs OCR / corrupt / empty) or unrecognized: fall back to the filename.
            String[] fd = filenameDoc(stem);
            if (row.status != FileStatus.OK || fd[0] != null || !Text.isBlank(text)) {
                document(ex, row, fd[1], fd[0], fd[0] != null ? fd[0] : stem,
                        "unread", row.status == FileStatus.OK ? null : Boolean.TRUE);
            }
        }
        if (ex.doc == null) {
            return ex;   // blank, unrecognized file: nothing to link
        }
        // Filename hints: 'INV-8002_Acme Corporation', 'DN-6041_Whitmore', 'GB-40_—_Datasheet_2'
        String filenameCompany = filenameDoc(stem)[2];
        if (filenameCompany != null) {
            Integer c = ex.addMentionWithConfidence("company", filenameCompany, "filename", 0.7, "truncated", Boolean.TRUE);
            if (PREFIX_TYPES.containsValue(ex.docMention().attrs.get("doc_type"))) {
                ex.fact(ex.doc, "ISSUED_TO", c);
            }
        }
        Matcher pm = Pattern.compile("^([A-Z]{2,4}-\\d{2,4})(?:-\\d+)?_").matcher(stem);
        if (pm.lookingAt() && !Pattern.compile("^(INV|QUO|PO|DN|DWG|CAL)-").matcher(stem).lookingAt()) {
            ex.fact(ex.doc, "DESCRIBES", ex.addMentionWithConfidence("product", pm.group(1), "filename", 0.8, "code", pm.group(1)));
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

    // TODO revisit this
    private Integer[] folderContext(Extraction ex, FileRow row) {
        Integer company = null;
        Integer project = null;
        
        if (row.folderCompany != null) {
            company = ex.addMention("company", row.folderCompany, "folder");
        }
        
        if (row.folderJob != null) {
            Matcher jobMention = Ingestor.JOB_DIR.matcher(row.folderJob);
            if (jobMention.matches()) {
                project = ex.addMention("project", jobMention.group(2), "folder", "job_id", jobMention.group(1), "company_mention", company);
                ex.fact(company, "HAS_PROJECT", project);
            }
        }
        return new Integer[] {company, project};
    }

    // ================================================================ stage runner

    /** Extracts every readable file and stores mentions, facts and issues. */
    public static Map<String, Integer> run(Connection conn) throws Exception {
        return new Extractor().extractAll(conn);
    }

    private Map<String, Integer> extractAll(Connection conn) throws Exception {
        List<FileRow> files = filesDao.findExcept(conn, FileStatus.SKIPPED, FileKind.ZIP);
        
        Map<Long, Long> docMentionOfFile = new HashMap<>();
        int mentions = 0;
        int facts = 0;
        
        for (FileRow row : files) {
            Extraction ex = extractFile(row);
            List<Long> ids = new ArrayList<Long>();
            for (Extraction.Mention mt : ex.mentions) {
                ids.add(mentionsDao.insert(conn, row.id, mt.etype, mt.surface, mt.role, mt.confidence));
            }
            // local mention indices in attrs (company_mention, org_mention) become database ids
            for (int i = 0; i < ex.mentions.size(); i++) {
                Map<String, Object> attrs = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> e : ex.mentions.get(i).attrs.entrySet()) {
                    Object v = e.getValue();
                    attrs.put(e.getKey(), e.getKey().endsWith("_mention") ? ids.get((Integer) v) : v);
                }
                mentionsDao.updateAttrs(conn, ids.get(i), attrs);
            }
            for (Extraction.Fact f : ex.facts) {
                factsDao.insert(conn, row.id, ids.get(f.src), f.rel, ids.get(f.dst));
            }
            for (String[] issue : ex.issues) {
                issuesDao.insert(conn, issue[0], issue[1], issue[2], row.id, null);
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
                factsDao.insert(conn, row.id, docMentionOfFile.get(row.id), "ATTACHED_TO", docMentionOfFile.get(row.parentId));
            }
        }
        fileIssues(conn);
        Db.commit(conn);
        Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
        stats.put("files", files.size());
        stats.put("mentions", mentions);
        stats.put("facts", facts);
        return stats;
    }

    /** Findings about the files themselves: wrong extension, unreadable, waiting for OCR. */
    private void fileIssues(Connection conn) throws Exception {
        Map<String, FileKind> extKinds = new HashMap<String, FileKind>();   // extension -> the kind it promises
        extKinds.put(".pdf", FileKind.PDF);
        extKinds.put(".docx", FileKind.DOCX);
        extKinds.put(".xlsx", FileKind.XLSX);
        extKinds.put(".png", FileKind.PNG);
        extKinds.put(".jpg", FileKind.JPG);
        extKinds.put(".eml", FileKind.EML);
        for (FileRow f : filesDao.findExcept(conn, FileStatus.SKIPPED)) {
            if (f.ext != null && extKinds.containsKey(f.ext) && extKinds.get(f.ext) != f.kind) {
                issuesDao.insert(conn, "mislabelled", "info", "extension " + f.ext + " but content is " + f.kind.value(), f.id, null);
            }
            if (f.status == FileStatus.CORRUPT || f.size == 0) {
                issuesDao.insert(conn, "unreadable", "error", f.error != null ? f.error : "empty file (0 bytes)", f.id, null);
            } else if (f.status == FileStatus.NEEDS_OCR) {
                issuesDao.insert(conn, "needs_ocr", "info", "image-only; content not read (enable an OCR backend)", f.id, null);
            }
        }
    }
}
