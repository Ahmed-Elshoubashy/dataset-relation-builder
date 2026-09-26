package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;
import static com.dubsof.graph.extract.parsers.ParserUtils.orEmpty;

/** Technical drawings (Drawing No: DWG-…): customer, job and who drew it. */
public class DrawingParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        String no = field(text, "Drawing No", "(DWG-\\d+)");
        if (no == null) {
            return false;
        }
        String title = field(text, "Title", "(.+)");
        Integer doc = document(ex, row, "drawing", no, title != null ? title : no, "revision", field(text, "Rev", "(.+)"));
        Integer customer = ex.addMention("company", orEmpty(field(text, "Customer", "(.+)")), "drawing_customer");
        ex.fact(doc, "ISSUED_TO", customer);
        String job = field(text, "Job", "(.+)");
        if (job != null) {
            ex.fact(ex.addMentionWithConfidence("project", job, "doc_job_field", 0.9, "company_mention", customer != null ? customer : folderCompany),
                    "HAS_DOCUMENT", doc);
        }
        String by = field(text, "Drawn By", "(.+)");
        if (by != null) {
            ex.fact(personWithOrg(ex, by, "drawn_by", owner(ex)), "AUTHORED", doc);
        }
        return true;
    }
}
