package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Text;

import java.util.List;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;

/** Price-list exports and item spreadsheets. */
public class ItemListParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        List<String> lines = Text.lines(text);
        if (lines.isEmpty()) {
            return false;
        }
        String first = Text.firstField(lines.get(0), "\\s*[|,]\\s*").toLowerCase();
        if (!first.equals("item") && !first.equals("description")) {
            return false;
        }
        String type = row.path.toLowerCase().contains("price") ? "price_list" : "item_list";
        Integer doc = document(ex, row, type, null, stem(row.path).replace("_", " "));
        for (String l : lines.subList(1, lines.size())) {
            String cell = Text.firstField(l, "\\s*[|,]\\s*");
            String code = ex.documentNumbers.productCode(cell);
            if (code != null) {
                ex.fact(doc, RelationType.LISTS_PRODUCT, ex.addMention(EntityType.PRODUCT, cell, MentionRole.LINE_ITEM, "code", code));
            }
        }
        return true;
    }
}
