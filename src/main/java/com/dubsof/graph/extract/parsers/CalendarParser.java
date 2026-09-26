package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.ingest.FileKind;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;
import static com.dubsof.graph.extract.parsers.ParserUtils.orEmpty;

/** Calendar invites (.ics): site visits name a project and a company. */
public class CalendarParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        if (row.kind != FileKind.ICS) {
            return false;
        }
        String summary = orEmpty(field(text, "SUMMARY", "(.+)"));
        String uid = field(text, "UID", "(.+)");
        Integer doc = document(ex, row, "meeting", "event:" + (uid != null ? uid : stem(row.path)), summary,
                "date", field(text, "DTSTART", "(.+)"));
        Matcher m = Pattern.compile("^Site visit\\s+\\W\\s+(.+?)\\s+\\((.+)\\)$").matcher(summary);
        if (m.matches()) {
            Integer company = ex.addMention("company", m.group(2), "calendar_summary");
            ex.fact(doc, "ADDRESSED_TO", company);
            ex.fact(ex.addMentionWithConfidence("project", m.group(1), "calendar_summary", 0.9, "company_mention", company != null ? company : folderCompany),
                    "HAS_DOCUMENT", doc);
        }
        return true;
    }
}
