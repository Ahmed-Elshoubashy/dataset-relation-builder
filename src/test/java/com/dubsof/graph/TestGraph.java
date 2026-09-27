package com.dubsof.graph;

import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.Dataset;
import com.dubsof.graph.dataset.FolderContext;
import com.dubsof.graph.dataset.Owner;
import com.dubsof.graph.dataset.Profile;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.extract.Extractor;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.read.TextSource;
import com.dubsof.graph.relate.Relator;
import com.dubsof.graph.resolve.Resolver;
import com.dubsof.graph.resolve.RuleAdjudicator;
import com.dubsof.graph.util.Text;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;

/** Builds small graphs for tests: a temporary graph.db, files with their text, and the real stages. */
public final class TestGraph {

    /** The john-doe dataset: its profile (profiles/john-doe.json) and its owner, as detected from the letterheads. */
    public static final Dataset JOHN_DOE = new Dataset(
            new Owner("Meridian Packaging Systems Ltd", "meridianpackaging.co.uk"), johnDoeProfile());

    private static final FilesDao filesDao = new FilesDao();

    private TestGraph() {
    }

    /** An empty graph.db in {@code dir}, with manual commits like the real pipeline. */
    public static Connection create(Path dir) throws Exception {
        Connection conn = Db.open(dir.resolve("graph.db").toFile(), false);
        Db.init(conn);
        return conn;
    }

    public static Profile johnDoeProfile() {
        try {
            return Profile.read(new File("profiles/john-doe.json"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A file row as the Ingestor would build it, without a database. The folder context comes from the path,
     * read with the john-doe profile's folder layout, like the Ingestor does.
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
        FolderContext ctx = JOHN_DOE.profile.folderContext(path);
        row.area = ctx.area;
        row.folderCompany = ctx.company;
        row.folderJob = ctx.job;
        row.folderJobId = ctx.jobId;
        row.folderJobTitle = ctx.jobTitle;
        row.folderCategory = ctx.category;
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
    public static void buildGraph(Connection conn, Dataset dataset) throws Exception {
        Extractor.run(conn, dataset, null);
        new Resolver(conn, new RuleAdjudicator(), dataset).run();
        Relator.run(conn, dataset);
    }
}
