package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;

/** One parser per document template. */
public interface Parser {

    /** Adds the file's mentions and facts to {@code ex}; returns false (adding nothing) when the file is not this template. */
    boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project);
}
