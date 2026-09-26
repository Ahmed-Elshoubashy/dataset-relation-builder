package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.util.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.DATE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;
import static com.dubsof.graph.extract.parsers.ParserUtils.join;

/** Letters: date, recipient address block, subject, and who signed. */
public class LetterParser implements Parser {

    private static final Pattern SIGNOFF = Pattern.compile("^(Yours faithfully|Yours sincerely|Kind regards|Best regards|Regards|Many thanks),?$",
            Pattern.CASE_INSENSITIVE);

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        List<String> lines = Text.lines(text);
        Integer dateI = null;
        Integer bodyI = null;
        boolean signed = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (dateI == null && i < 12 && l.matches(DATE)) {
                dateI = i;
            }
            if (bodyI == null && (l.startsWith("Re:") || l.startsWith("Dear "))) {
                bodyI = i;
            }
            signed |= SIGNOFF.matcher(l).matches();
        }
        if (dateI == null || bodyI == null || bodyI <= dateI || !signed) {
            return false;
        }
        String stem = stem(row.path);
        Matcher n = Pattern.compile("\\d+").matcher(stem);
        String key = stem.toLowerCase().startsWith("letter") && n.find() ? "LETTER-" + n.group() : null;
        String subject = lines.get(bodyI).startsWith("Re:") ? lines.get(bodyI).substring(3).trim() : null;
        Integer doc = document(ex, row, "letter", key, key != null ? key : stem, "date", lines.get(dateI), "subject", subject);
        List<String> block = lines.subList(dateI + 1, bodyI);
        if (!block.isEmpty()) {
            String address = join(block.subList(1, block.size()), ", ");
            ex.fact(doc, RelationType.ADDRESSED_TO, ex.addMention(EntityType.COMPANY, block.get(0), MentionRole.LETTER_RECIPIENT, "address", address.isEmpty() ? null : address));
        }
        String job = subject != null && subject.contains("·") ? subject.split("·", 2)[1].trim() : null;
        if (job == null) {
            Matcher m = Pattern.compile("(?:regarding|progress with|schedule for|concerning)\\s+([A-Z][\\w&\\- ]+?)[,.]").matcher(text);
            if (m.find()) {
                job = m.group(1);
            }
        }
        if (job != null) {
            ex.fact(ex.addMentionWithConfidence(EntityType.PROJECT, job, MentionRole.DOC_JOB_FIELD, 0.9, "company_mention", folderCompany), RelationType.HAS_DOCUMENT, doc);
        }
        String ownerFirstWord = Config.ownerName.split(" ")[0];
        for (int i = 0; i < lines.size(); i++) {
            if (SIGNOFF.matcher(lines.get(i)).matches() && i + 1 < lines.size()) {
                Integer org = i + 2 < lines.size() && lines.get(i + 2).contains(ownerFirstWord) ? owner(ex) : null;
                String jobTitle = i + 2 < lines.size() ? lines.get(i + 2).split(",")[0] : null;
                ex.fact(personWithOrg(ex, lines.get(i + 1), MentionRole.SIGNATORY, org, "job_title", jobTitle), RelationType.AUTHORED, doc);
            }
        }
        return true;
    }
}
