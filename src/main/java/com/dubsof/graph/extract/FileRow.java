package com.dubsof.graph.extract;

import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;

import java.util.Map;

/** The columns of a {@code files} row that the extractors need. */
public class FileRow {
    public long id;
    public Long parentId;
    public String path;
    public String sha256;
    public FileKind kind;
    public FileStatus status;
    public String text;
    public String area;
    public String folderCompany;
    public String folderJob;

    public static FileRow of(Map<String, Object> r) {
        FileRow f = new FileRow();
        f.id = ((Number) r.get("id")).longValue();
        f.parentId = r.get("parent_id") == null ? null : ((Number) r.get("parent_id")).longValue();
        f.path = (String) r.get("path");
        f.sha256 = (String) r.get("sha256");
        f.kind = FileKind.fromValue((String) r.get("kind"));
        f.status = FileStatus.fromValue((String) r.get("status"));
        f.text = (String) r.get("text");
        f.area = (String) r.get("area");
        f.folderCompany = (String) r.get("folder_company");
        f.folderJob = (String) r.get("folder_job");
        return f;
    }
}
