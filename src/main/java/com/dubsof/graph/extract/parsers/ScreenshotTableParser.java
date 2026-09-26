package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static com.dubsof.graph.extract.parsers.ParserUtils.JOB_ID;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.orEmpty;

/** OCR'd app screenshots: tables whose rows carry a JOB code and a customer name. */
public class ScreenshotTableParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        List<String> lines = Text.lines(text);
        List<String[]> rows = new ArrayList<String[]>();
        List<String> header = new ArrayList<String>();
        for (String l : lines) {
            if (JOB_ID.matcher(l).find()) {
                String[] cells = l.split("\\|", -1);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].trim();
                }
                rows.add(cells);
            } else if (header.isEmpty() && l.contains("|")) {
                for (String h : l.split("\\|", -1)) {
                    header.add(h.toLowerCase());
                }
            }
        }
        if (rows.isEmpty()) {
            return false;
        }
        String title = Text.truncate("Screenshot: " + lines.get(0), 80);
        Integer doc = document(ex, row, "screenshot", null, title);
        for (String[] cells : rows) {
            int jobI = 0;
            while (!JOB_ID.matcher(cells[jobI]).find()) {
                jobI++;
            }
            Map<String, String> attrs = new HashMap<String, String>();
            for (int i = 0; i < header.size() && i < cells.length; i++) {
                if (i != jobI && !header.get(i).trim().isEmpty()) {
                    attrs.put(header.get(i).trim(), cells[i]);
                }
            }
            String customer = attrs.containsKey("customer") ? attrs.get("customer")
                    : jobI + 1 < cells.length ? cells[jobI + 1] : null;
            Integer company = ex.addMention("company", orEmpty(customer), "screenshot_row");
            Matcher jm = JOB_ID.matcher(cells[jobI]);
            jm.find();
            Integer pj = ex.addMentionWithConfidence("project", jm.group(), "screenshot_row", 0.9, "job_id", jm.group(),
                    "company_mention", company, "status", attrs.get("status"), "value", attrs.get("value"));
            ex.fact(company, "HAS_PROJECT", pj);
            ex.fact(pj, "HAS_DOCUMENT", doc);
        }
        return true;
    }
}
