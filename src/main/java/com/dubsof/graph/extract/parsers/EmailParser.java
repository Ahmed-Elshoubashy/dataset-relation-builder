package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.MentionRole;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.FileKind;

import javax.mail.internet.InternetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.nameFromAddress;
import static com.dubsof.graph.extract.parsers.ParserUtils.refs;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.addresses;
import static com.dubsof.graph.extract.parsers.ParserUtils.companyFromDomain;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;

/** E-mails (.eml): sender and recipients as people of their domain's company, plus the project in the subject. */
public class EmailParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        if (row.kind != FileKind.EML) {
            return false;
        }
        int split = text.indexOf("\n\n");
        String head = split >= 0 ? text.substring(0, split) : text;
        String body = split >= 0 ? text.substring(split + 2) : "";
        Map<String, String> hdr = new HashMap<String, String>();
        Matcher hm = Pattern.compile("^(From|To|Cc|Date|Subject): (.*)$", Pattern.MULTILINE).matcher(head);
        while (hm.find()) {
            hdr.put(hm.group(1), hm.group(2));
        }
        String subject = hdr.containsKey("Subject") ? hdr.get("Subject") : "";
        String stem = stem(row.path);
        Integer doc = document(ex, row, "email", "email:" + stem, subject.isEmpty() ? stem : subject,
                "date", hdr.get("Date"), "subject", subject);
        String[] headers = {"From", "To", "Cc"};
        MentionRole[] roles = {MentionRole.EMAIL_FROM, MentionRole.EMAIL_TO, MentionRole.EMAIL_CC};
        for (int i = 0; i < headers.length; i++) {
            for (InternetAddress a : addresses(hdr.get(headers[i]))) {
                String addr = a.getAddress();
                if (addr == null || addr.isEmpty()) {
                    continue;
                }
                Integer org = companyFromDomain(ex, addr);
                String name = a.getPersonal() != null ? a.getPersonal() : nameFromAddress(addr);
                RelationType rel = headers[i].equals("From") ? RelationType.SENT : RelationType.RECEIVED;
                ex.fact(personWithOrg(ex, name, roles[i], org, "email", addr.toLowerCase()), rel, doc);
            }
        }
        // project by title in the subject or body
        String title = null;
        String[] subjectPatterns = {"^(?:RE|Fwd|FW):\\s*(.+?)\\s+(?:—|-)\\s+schedule update", "^(?:RE|Fwd|FW):\\s*(.+?) site visit$"};
        for (String pat : subjectPatterns) {
            Matcher m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(subject);
            if (m.lookingAt()) {
                title = m.group(1);
            }
        }
        if (title == null) {
            Matcher m = Pattern.compile("(?:concerning|regarding|schedule for|relating to)\\s+([A-Z][\\w&\\- ]+?)[,.]").matcher(body);
            if (m.find()) {
                title = m.group(1);
            }
        }
        if (title != null) {
            ex.fact(ex.addMentionWithConfidence(EntityType.PROJECT, title, MentionRole.EMAIL_SUBJECT, 0.8, "company_mention", folderCompany), RelationType.HAS_DOCUMENT, doc);
        }
        refs(ex, subject + "\n" + body, null);
        return true;
    }
}
