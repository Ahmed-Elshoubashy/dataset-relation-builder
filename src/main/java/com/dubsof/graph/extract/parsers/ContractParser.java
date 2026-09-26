package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;
import static com.dubsof.graph.extract.parsers.ParserUtils.baseStem;

/** Contracts ("Between X and Y"): the two parties and the project. */
public class ContractParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        Matcher m = Pattern.compile("^Between (.+?) and (.+)$", Pattern.MULTILINE).matcher(text);
        if (!m.find()) {
            return false;
        }
        String kind = Text.lines(text).get(0);
        String stem = baseStem(stem(row.path));
        String key = "contract:" + (row.folderJob != null ? row.folderJob : row.path) + ":" + stem;
        Integer doc = document(ex, row, "contract", key, kind + " (" + stem.replace("Contract_", "").replace("_", " ") + ")");
        ex.fact(ex.addMention(EntityType.COMPANY, m.group(1), MentionRole.CONTRACT_PARTY), RelationType.PARTY_TO, doc);
        ex.fact(ex.addMention(EntityType.COMPANY, m.group(2), MentionRole.CONTRACT_PARTY), RelationType.PARTY_TO, doc);
        String job = field(text, "Relating to", "(.+)");
        if (job != null) {
            ex.fact(ex.addMentionWithConfidence(EntityType.PROJECT, job, MentionRole.DOC_JOB_FIELD, 0.9, "company_mention", folderCompany), RelationType.HAS_DOCUMENT, doc);
        }
        return true;
    }
}
