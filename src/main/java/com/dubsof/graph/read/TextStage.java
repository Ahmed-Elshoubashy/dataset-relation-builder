package com.dubsof.graph.read;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.pipeline.Progress;
import com.dubsof.graph.util.Text;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Stage 2: get text out of every file.
 * Native parsers first; anything image-only goes to the configured OCR backend.
 */
public class TextStage {

    private static final FilesDao filesDao = new FilesDao();
    private static final Pattern PHOTO_NAME = Pattern.compile("^(IMG|MKT)_\\d+\\.jpe?g$", Pattern.CASE_INSENSITIVE);

    /** Thrown by a native parser when the file has no text layer. */
    static class NeedsOcr extends Exception {
    }

    /**
     * @param ocrBackend "claude", "tesseract" or "none"
     * @param apiKey     only for Claude; used for this run and never stored
     */
    public static Map<String, Integer> run(Connection conn, String ocrBackend, String apiKey, Progress progress) throws Exception {
        Map<String, Integer> stats = new LinkedHashMap<String, Integer>();
        for (String k : new String[] {"native", "ocr", "ocr_pending", "corrupt", "empty", "skipped_photos"}) {
            stats.put(k, 0);
        }
        List<FileRow> rows = filesDao.findOriginalsWithStatus(conn, FileStatus.NEW, FileStatus.NEEDS_OCR);
        List<FileRow> ocrQueue = new ArrayList<FileRow>();
        for (FileRow row : rows) {
            if (row.kind == FileKind.ZIP) {
                filesDao.updateStatus(conn, row.id, FileStatus.CONTAINER);
                continue;
            }
            byte[] data = Files.readAllBytes(new File(row.blobPath).toPath());
            try {
                String text = Text.truncate(readNative(row.kind, data), Config.MAX_TEXT_CHARS);
                FileStatus status = Text.isBlank(text) ? FileStatus.EMPTY : FileStatus.OK;
                increment(stats, status == FileStatus.OK ? "native" : "empty");
                filesDao.updateText(conn, row.id, text, "native", status);
            } catch (NeedsOcr e) {
                if (wantsOcr(row)) {
                    ocrQueue.add(row);
                } else {
                    increment(stats, "skipped_photos");
                    filesDao.updateStatus(conn, row.id, FileStatus.EMPTY);
                }
            } catch (Exception e) {   // unreadable, truncated or mislabelled files
                increment(stats, "corrupt");
                filesDao.updateStatus(conn, row.id, FileStatus.CORRUPT,
                        Text.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500));
            }
        }
        Db.commit(conn);

        if (!ocrQueue.isEmpty()) {
            runOcr(conn, ocrQueue, ocrBackend, apiKey, progress, stats);
        }
        stats.put("ocr_pending", (int) filesDao.countWithStatus(conn, FileStatus.NEEDS_OCR));

        // Byte-identical duplicates share their original's text.
        filesDao.copyTextToDuplicates(conn);
        Db.commit(conn);
        return stats;
    }

    private static void runOcr(Connection conn, List<FileRow> queue, String backend, String apiKey,
                               Progress progress, Map<String, Integer> stats) throws Exception {
        final CachedReader reader;
        try {
            TextReader inner = Readers.create(backend, apiKey);
            if (inner instanceof NullReader) {
                throw new ReaderUnavailableException("OCR is switched off");
            }
            reader = new CachedReader(inner);
        } catch (ReaderUnavailableException e) {
            progress.update(2, "read", "OCR unavailable (" + e.getMessage() + "); " + queue.size() + " image-only files left unread");
            for (FileRow row : queue) {
                filesDao.updateStatus(conn, row.id, FileStatus.NEEDS_OCR);
            }
            Db.commit(conn);
            return;
        }
        progress.update(2, "read", "OCR via " + reader.name() + ": " + queue.size() + " files");
        ExecutorService pool = Executors.newFixedThreadPool(Config.OCR_WORKERS);
        try {
            CompletionService<String[]> done = new ExecutorCompletionService<String[]>(pool);
            for (final FileRow row : queue) {
                done.submit(new Callable<String[]>() {
                    /** Returns {file id, text, error}: exactly one of text / error is set. */
                    public String[] call() {
                        String id = String.valueOf(row.id);
                        try {
                            byte[] data = Files.readAllBytes(new File(row.blobPath).toPath());
                            String text = reader.readWithHash(row.sha256, data, row.kind, row.path);
                            return new String[] {id, text, null};
                        } catch (Exception e) {
                            return new String[] {id, null, "OCR failed: " + e.getMessage()};
                        }
                    }
                });
            }
            // results are written from this thread only: SQLite likes a single writer
            for (int i = 1; i <= queue.size(); i++) {
                String[] r = done.take().get();
                if (r[2] == null) {
                    filesDao.updateText(conn, Long.parseLong(r[0]), r[1], reader.name(),
                            Text.isBlank(r[1]) ? FileStatus.EMPTY : FileStatus.OK);
                    increment(stats, "ocr");
                } else {   // stays waiting for the next OCR run
                    filesDao.updateStatus(conn, Long.parseLong(r[0]), FileStatus.NEEDS_OCR, Text.truncate(r[2], 500));
                }
                if (i % 5 == 0 || i == queue.size()) {
                    Db.commit(conn);
                    progress.update(2, "read", "OCR via " + reader.name() + ": " + i + "/" + queue.size() + " files");
                }
            }
        } finally {
            pool.shutdown();
            reader.close();
        }
    }

    private static boolean wantsOcr(FileRow row) {
        String[] members = row.path.split(Pattern.quote("::"));
        String name = members[members.length - 1];
        name = name.substring(name.lastIndexOf('/') + 1);
        FileKind kind = row.kind;
        if (kind == FileKind.JPG && PHOTO_NAME.matcher(name).matches() && !Config.OCR_PHOTOS) {
            return false;
        }
        if (name.equals("site_photo.png") && !Config.OCR_PHOTOS) {   // photos attached to emails
            return false;
        }
        return kind.isOcrable();
    }

    private static void increment(Map<String, Integer> stats, String key) {
        stats.put(key, stats.get(key) + 1);
    }

    // ------------------------------------------------------------------ native parsers

    static String readNative(FileKind kind, byte[] data) throws Exception {
        switch (kind) {
            case PDF:
                return pdf(data);
            case EML:
                return email(data);
            case DOCX:
                return docx(data);
            case XLSX:
                return xlsx(data);
            case RTF:
                return rtf(data);
            case TEXT:
            case VCF:
            case ICS:
                return Text.utf8OrLatin1(data);
            default:
                throw new NeedsOcr();   // png, jpg, and anything else without text
        }
    }

    private static String pdf(byte[] data) throws Exception {
        try (PDDocument doc = Loader.loadPDF(data)) {
            String text = new PDFTextStripper().getText(doc);
            if (Text.isBlank(text)) {
                throw new NeedsOcr();
            }
            return text;
        }
    }

    private static String email(byte[] data) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data));
        StringBuilder sb = new StringBuilder();
        for (String h : new String[] {"From", "To", "Cc", "Date", "Subject"}) {
            String v = msg.getHeader(h, ", ");
            if (v != null) {
                sb.append(h).append(": ").append(MimeUtility.decodeText(MimeUtility.unfold(v))).append('\n');
            }
        }
        sb.append('\n');
        String body = plainBody(msg);
        return sb.append(body == null ? "" : body).toString();
    }

    private static String plainBody(Part part) throws Exception {
        if (part.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String body = plainBody(mp.getBodyPart(i));
                if (body != null) {
                    return body;
                }
            }
        }
        return null;
    }

    private static String docx(byte[] data) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<String>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText());
                    }
                    sb.append(join(cells)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static String xlsx(byte[] data) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : wb) {
                sb.append("# sheet: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<String>();
                    boolean any = false;
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        String v = cellText(row.getCell(i));
                        any |= !v.isEmpty();
                        cells.add(v);
                    }
                    if (any) {
                        sb.append(join(cells)).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (type == CellType.BOOLEAN) {
            return cell.getBooleanCellValue() ? "True" : "False";
        }
        return type == CellType.STRING ? cell.getStringCellValue() : "";
    }

    private static String rtf(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
        s = s.replaceAll("\\\\pard?", "\n");
        s = s.replaceAll("(?s)\\{\\\\fonttbl.*?\\}\\}?", "");
        s = s.replaceAll("\\\\[a-z]+-?\\d* ?", "");
        return s.replace("{", "").replace("}", "").trim();
    }

    private static String join(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(cells.get(i));
        }
        return sb.toString();
    }
}
