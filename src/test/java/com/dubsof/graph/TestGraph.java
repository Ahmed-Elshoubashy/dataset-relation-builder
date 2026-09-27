package com.dubsof.graph;

import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.Extractor;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.read.TextSource;
import com.dubsof.graph.relate.Relator;
import com.dubsof.graph.resolve.Resolver;
import com.dubsof.graph.resolve.RuleAdjudicator;
import com.dubsof.graph.util.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;

/** Builds small graphs for tests: a temporary graph.db, files with their text, and the real stages. */
public final class TestGraph {

    private static final FilesDao filesDao = new FilesDao();

    private TestGraph() {
    }

    /** An empty graph.db in {@code dir}, with manual commits like the real pipeline. */
    public static Connection create(Path dir) throws Exception {
        Connection conn = Db.open(dir.resolve("graph.db").toFile(), false);
        Db.init(conn);
        return conn;
    }

    /**
     * A file row as the Ingestor would build it, without a database. The folder context comes from the path,
     * like the Ingestor does: "Customers/&lt;company&gt;/&lt;JOB-... title&gt;/&lt;category&gt;/name".
     * {@code text} null means the file could not be read (status needs_ocr).
     */
    public static FileRow row(String path, FileKind kind, String text) {
        FileRow row = new FileRow();
        row.id = 1;
        row.path = path;
        row.kind = kind;
        row.text = text;
        row.status = text == null ? FileStatus.NEEDS_OCR : FileStatus.OK;
        row.sha256 = Text.sha256(path.getBytes(StandardCharsets.UTF_8));
        row.blobPath = "unused";
        row.size = text == null ? 100 : text.length();
        String[] parts = path.split("/");
        row.area = parts.length > 1 ? parts[0] : null;
        if (parts[0].equals("Customers") && parts.length > 2) {
            row.folderCompany = parts[1];
            if (parts.length > 3 && parts[2].startsWith("JOB-")) {
                row.folderJob = parts[2];
                row.folderCategory = parts.length > 4 ? parts[3] : null;
            }
        }
        return row;
    }

    /** Stores a file (and its text, if it has any) in the graph.db, as ingest + read would. */
    public static long addFile(Connection conn, String path, FileKind kind, String text) throws Exception {
        FileRow row = row(path, kind, text);
        FileStatus finalStatus = row.status;
        row.status = FileStatus.NEW;
        long id = filesDao.insertIfAbsent(conn, row);
        if (text != null) {
            filesDao.updateText(conn, id, text, TextSource.NATIVE, FileStatus.OK);
        } else {
            filesDao.updateStatus(conn, id, finalStatus);
        }
        return id;
    }

    /** Runs extract, resolve (with the default rule adjudicator) and relate on the files already stored. */
    public static void buildGraph(Connection conn) throws Exception {
        Extractor.run(conn);
        new Resolver(conn, new RuleAdjudicator()).run();
        Relator.run(conn);
    }
}
