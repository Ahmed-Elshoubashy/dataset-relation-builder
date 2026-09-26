package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.Extraction;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;

/** Technical specifications and datasheets ("HL-6200 · Datasheet"): the product they describe. */
public class SpecParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        Matcher head = Pattern.compile("^([A-Z]{2,4}-\\d{2,4}) · (Technical Specification|Datasheet)", Pattern.MULTILINE).matcher(text);
        if (!head.find()) {
            return false;
        }
        Matcher no = Pattern.compile("Doc No:\\s*(\\S+)").matcher(text);
        Matcher model = Pattern.compile("Model:\\s*(\\S+)").matcher(text);
        String docType = head.group(2).equals("Datasheet") ? "datasheet" : "specification";
        String key = no.find() ? no.group(1) : null;
        String titleCode = head.group(1);
        Integer doc = document(ex, row, docType, key, titleCode + " " + head.group(2) + (key != null ? " (" + key + ")" : ""));
        ex.fact(doc, "DESCRIBES", ex.addMention("product", titleCode, "spec_title", "code", titleCode));
        if (model.find() && !model.group(1).equals(titleCode)) {
            ex.issue("conflict", "info", "titled " + titleCode + " but body says Model: " + model.group(1));
        }
        return true;
    }
}
