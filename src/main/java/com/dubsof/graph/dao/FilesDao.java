package com.dubsof.graph.dao;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.read.TextSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** All SQL for the {@code files} table. */
public class FilesDao {

    private static final String INSERT_IF_ABSENT =
            "INSERT OR IGNORE INTO files (path, parent_id, blob_path, sha256, size, ext, kind,"
                    + " area, folder_company, folder_job, folder_job_id, folder_job_title, folder_category, status)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    private static final String MARK_DUPLICATES =
            "UPDATE files SET duplicate_of = (SELECT MIN(f2.id) FROM files f2 WHERE f2.sha256 = files.sha256)"
                    + " WHERE id != (SELECT MIN(f2.id) FROM files f2 WHERE f2.sha256 = files.sha256)";
    private static final String SELECT_BY_ID = "SELECT * FROM files WHERE id = ?";
    private static final String SELECT_ALL = "SELECT * FROM files ORDER BY id";
    private static final String SELECT_ORIGINALS_BY_STATUS =
            "SELECT * FROM files WHERE status IN (?, ?) AND duplicate_of IS NULL ORDER BY id";
    private static final String SELECT_EXCEPT_STATUS = "SELECT * FROM files WHERE status != ? ORDER BY id";
    private static final String SELECT_EXCEPT_STATUS_AND_KIND =
            "SELECT * FROM files WHERE status != ? AND kind != ? ORDER BY id";
    private static final String SELECT_TEXTS =
            "SELECT * FROM files WHERE kind = ? AND status = ? AND (? IS NULL OR text_source = ?) ORDER BY id";
    private static final String SELECT_WITH_TEXT = "SELECT * FROM files WHERE status = ? AND text IS NOT NULL ORDER BY id";
    private static final String UPDATE_STATUS = "UPDATE files SET status = ? WHERE id = ?";
    private static final String UPDATE_STATUS_AND_ERROR = "UPDATE files SET status = ?, error = ? WHERE id = ?";
    private static final String UPDATE_TEXT = "UPDATE files SET text = ?, text_source = ?, status = ?, error = NULL WHERE id = ?";
    private static final String COPY_TEXT_TO_DUPLICATES =
            "UPDATE files SET (text, text_source, status, error) ="
                    + " (SELECT o.text, o.text_source, o.status, o.error FROM files o WHERE o.id = files.duplicate_of)"
                    + " WHERE duplicate_of IS NOT NULL AND status != ?";
    private static final String COUNT_WITH_STATUS = "SELECT COUNT(*) FROM files WHERE status = ?";
    private static final String COUNTS_BY_STATUS = "SELECT status, COUNT(*) FROM files GROUP BY status";

    /** Adds a file unless its path is already recorded. Returns the new id, or 0 when it was already there. */
    public long insertIfAbsent(Connection conn, FileRow f) throws SQLException {
        return Db.insert(conn, INSERT_IF_ABSENT, f.path, f.parentId, f.blobPath, f.sha256, f.size, f.ext, f.kind.value(),
                f.area, f.folderCompany, f.folderJob, f.folderJobId, f.folderJobTitle, f.folderCategory, f.status.value());
    }

    /** Points every byte-identical copy at the first file with the same content. */
    public void markDuplicates(Connection conn) throws SQLException {
        Db.update(conn, MARK_DUPLICATES);
    }

    public FileRow findById(Connection conn, long id) throws SQLException {
        return Db.first(conn, SELECT_BY_ID, FilesDao::map, id);
    }

    public List<FileRow> findAll(Connection conn) throws SQLException {
        return Db.list(conn, SELECT_ALL, FilesDao::map);
    }

    /** Files in one of two statuses that are not copies of another file (the read stage's work list). */
    public List<FileRow> findOriginalsWithStatus(Connection conn, FileStatus a, FileStatus b) throws SQLException {
        return Db.list(conn, SELECT_ORIGINALS_BY_STATUS, FilesDao::map, a.value(), b.value());
    }

    /** Every file except those with the given status. */
    public List<FileRow> findExcept(Connection conn, FileStatus status) throws SQLException {
        return Db.list(conn, SELECT_EXCEPT_STATUS, FilesDao::map, status.value());
    }

    /** Every file except those with the given status or kind (the extract stage's work list). */
    public List<FileRow> findExcept(Connection conn, FileStatus status, FileKind kind) throws SQLException {
        return Db.list(conn, SELECT_EXCEPT_STATUS_AND_KIND, FilesDao::map, status.value(), kind.value());
    }

    /** Files of one kind and status; {@code textSource} null means any source. */
    public List<FileRow> findTexts(Connection conn, FileKind kind, FileStatus status, TextSource textSource) throws SQLException {
        String source = textSource == null ? null : textSource.value();
        return Db.list(conn, SELECT_TEXTS, FilesDao::map, kind.value(), status.value(), source, source);
    }

    public List<FileRow> findWithText(Connection conn, FileStatus status) throws SQLException {
        return Db.list(conn, SELECT_WITH_TEXT, FilesDao::map, status.value());
    }

    public void updateStatus(Connection conn, long id, FileStatus status) throws SQLException {
        Db.update(conn, UPDATE_STATUS, status.value(), id);
    }

    public void updateStatus(Connection conn, long id, FileStatus status, String error) throws SQLException {
        Db.update(conn, UPDATE_STATUS_AND_ERROR, status.value(), error, id);
    }

    /** Stores the text read from a file, which source produced it, and the resulting status. */
    public void updateText(Connection conn, long id, String text, TextSource textSource, FileStatus status) throws SQLException {
        Db.update(conn, UPDATE_TEXT, text, textSource.value(), status.value(), id);
    }

    /** Gives byte-identical copies their original's text and status (except skipped files). */
    public void copyTextToDuplicates(Connection conn) throws SQLException {
        Db.update(conn, COPY_TEXT_TO_DUPLICATES, FileStatus.SKIPPED.value());
    }

    public long countWithStatus(Connection conn, FileStatus status) throws SQLException {
        return Db.number(conn, COUNT_WITH_STATUS, status.value());
    }

    /** status -> number of files. */
    public Map<String, Long> countsByStatus(Connection conn) throws SQLException {
        return Db.counts(conn, COUNTS_BY_STATUS);
    }

    private static FileRow map(ResultSet rs) throws SQLException {
        FileRow f = new FileRow();
        f.id = rs.getLong("id");
        f.path = rs.getString("path");
        f.parentId = Db.longOrNull(rs, "parent_id");
        f.blobPath = rs.getString("blob_path");
        f.sha256 = rs.getString("sha256");
        f.size = rs.getLong("size");
        f.ext = rs.getString("ext");
        f.kind = FileKind.fromValue(rs.getString("kind"));
        f.area = rs.getString("area");
        f.folderCompany = rs.getString("folder_company");
        f.folderJob = rs.getString("folder_job");
        f.folderJobId = rs.getString("folder_job_id");
        f.folderJobTitle = rs.getString("folder_job_title");
        f.folderCategory = rs.getString("folder_category");
        f.status = FileStatus.fromValue(rs.getString("status"));
        f.textSource = TextSource.fromValue(rs.getString("text_source"));
        f.text = rs.getString("text");
        f.error = rs.getString("error");
        f.duplicateOf = Db.longOrNull(rs, "duplicate_of");
        return f;
    }
}
