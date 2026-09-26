package com.dubsof.graph.dao.row;

import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;

/** One row of the {@code files} table: a file, zip member or e-mail attachment. */
public class FileRow {
    public long id;
    /** Path relative to the dataset; "::" separates archive / attachment members. */
    public String path;
    /** The zip or e-mail this file came out of, or null. */
    public Long parentId;
    /** Where the bytes are on disk (the original file, or a copy in data/blobs/). */
    public String blobPath;
    public String sha256;
    public long size;
    public String ext;
    public FileKind kind;
    public String area;
    public String folderCompany;
    public String folderJob;
    public String folderCategory;
    public FileStatus status;
    /** "native", "claude", "tesseract", or null while unread. */
    public String textSource;
    public String text;
    public String error;
    /** The first byte-identical copy, or null. */
    public Long duplicateOf;
}
