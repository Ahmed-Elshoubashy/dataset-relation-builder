package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.util.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.DATE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;

/** Service reports ("Prepared by X on <date>"). */
public class ServiceReportParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        Matcher m = Pattern.compile("Prepared by (.+?) on (" + DATE + ")").matcher(text);
        if (!m.find()) {
            return false;
        }
        Integer doc = document(ex, row, "report", null, Text.lines(text).get(0), "date", m.group(2));
        ex.fact(personWithOrg(ex, m.group(1), "report_author", owner(ex)), "AUTHORED", doc);
        return true;
    }
}
