package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Text;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;

/** Meeting notes: every attendee. */
public class MeetingNotesParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        String attendees = field(text, "Attendees", "(.+)");
        if (attendees == null) {
            return false;
        }
        Integer doc = document(ex, row, "meeting_notes", null, Text.lines(text).get(0));
        for (String name : attendees.split(",|\\band\\b")) {
            ex.fact(personWithOrg(ex, name, "attendee", null), RelationType.ATTENDED, doc);
        }
        return true;
    }
}
