package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.util.Text;

import java.util.List;
import java.util.regex.Matcher;

import static com.dubsof.graph.extract.parsers.ParserUtils.PRODUCT_CODE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;

/** Price-list exports and item spreadsheets. */
public class ItemListParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        List<String> lines = Text.lines(text);
        if (lines.isEmpty()) {
            return false;
        }
        String first = lines.get(0).split("\\s*[|,]\\s*")[0].toLowerCase();
        if (!first.equals("item") && !first.equals("description")) {
            return false;
        }
        String type = row.path.toLowerCase().contains("price") ? "price_list" : "item_list";
        Integer doc = document(ex, row, type, null, stem(row.path).replace("_", " "));
        for (String l : lines.subList(1, lines.size())) {
            String cell = l.split("\\s*[|,]\\s*")[0];
            Matcher m = PRODUCT_CODE.matcher(cell);
            if (m.find()) {
                ex.fact(doc, "LISTS_PRODUCT", ex.addMention(EntityType.PRODUCT, cell, "line_item", "code", m.group(1)));
            }
        }
        return true;
    }
}
