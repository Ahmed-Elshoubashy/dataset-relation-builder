package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.ingest.FileKind;

import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.refs;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.companyFromDomain;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;
import static com.dubsof.graph.extract.parsers.ParserUtils.join;

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
        String[][] roles = {{"From", "email_from"}, {"To", "email_to"}, {"Cc", "email_cc"}};   // header, mention role
        for (String[] r : roles) {
            for (InternetAddress a : addresses(hdr.get(r[0]))) {
                String addr = a.getAddress();
                if (addr == null || addr.isEmpty()) {
                    continue;
                }
                Integer org = companyFromDomain(ex, addr);
                String name = a.getPersonal() != null ? a.getPersonal() : nameFromAddress(addr);
                RelationType rel = r[0].equals("From") ? RelationType.SENT : RelationType.RECEIVED;
                ex.fact(personWithOrg(ex, name, r[1], org, "email", addr.toLowerCase()), rel, doc);
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
            ex.fact(ex.addMentionWithConfidence(EntityType.PROJECT, title, "email_subject", 0.8, "company_mention", folderCompany), RelationType.HAS_DOCUMENT, doc);
        }
        refs(ex, subject + "\n" + body, null);
        return true;
    }

    private static List<InternetAddress> addresses(String header) {
        List<InternetAddress> out = new ArrayList<InternetAddress>();
        if (header == null) {
            return out;
        }
        try {
            for (InternetAddress a : InternetAddress.parseHeader(header, false)) {
                out.add(a);
            }
        } catch (AddressException e) {
            // unparseable header: no people from it
        }
        return out;
    }

    /** "isla.patel@x.com" -> "Isla Patel" */
    private static String nameFromAddress(String addr) {
        List<String> words = new ArrayList<String>();
        for (String w : addr.substring(0, addr.indexOf('@')).split("\\.")) {
            words.add(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase());
        }
        return join(words, " ");
    }
}
