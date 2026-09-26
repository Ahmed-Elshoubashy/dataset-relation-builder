package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.util.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.DATE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;

/** Product manuals: product code on the first line, "Manual" on the second. */
public class ManualParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        List<String> lines = Text.lines(text);
        if (lines.size() < 2 || !lines.get(1).contains("Manual")) {
            return false;
        }
        Matcher m = Pattern.compile("^([A-Z]{2,4}-\\d{2,4})(?:-(\\d+))?$").matcher(lines.get(0));
        if (!m.matches()) {
            return false;
        }
        Matcher d = Pattern.compile(DATE).matcher(text);
        Integer doc = document(ex, row, "manual", "manual:" + lines.get(0), lines.get(0) + " " + lines.get(1),
                "date", d.find() ? d.group() : null);
        ex.fact(doc, "DESCRIBES", ex.addMention(EntityType.PRODUCT, m.group(1), "manual_title", "code", m.group(1)));
        return true;
    }
}
