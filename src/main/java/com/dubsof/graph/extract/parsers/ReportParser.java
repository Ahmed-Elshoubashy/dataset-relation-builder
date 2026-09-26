package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.RelationType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.DATE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;

/** Internal reports (Date: … Author: …). */
public class ReportParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        Matcher m = Pattern.compile("Date:\\s*(" + DATE + ")\\s+Author:\\s*(.+)").matcher(text);
        if (!m.find() || !text.contains("Internal Report")) {
            return false;
        }
        String stem = stem(row.path);
        Integer doc = document(ex, row, "report", "report:" + stem, stem.replace("_", " "), "date", m.group(1));
        ex.fact(personWithOrg(ex, m.group(2), "report_author", owner(ex)), RelationType.AUTHORED, doc);
        return true;
    }
}
