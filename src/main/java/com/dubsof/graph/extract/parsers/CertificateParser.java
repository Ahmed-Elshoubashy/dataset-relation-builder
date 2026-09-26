package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.util.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dubsof.graph.extract.parsers.ParserUtils.DATE;
import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.owner;
import static com.dubsof.graph.extract.parsers.ParserUtils.stem;
import static com.dubsof.graph.extract.parsers.ParserUtils.orEmpty;

/** ISO certificates (company + certification body) and training certificates (person + provider). */
public class CertificateParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        if (!text.contains("certify that") && !text.contains("certifies that")) {
            return false;
        }
        List<String> lines = Text.lines(text);
        int i = 0;
        while (i < lines.size() && !(lines.get(i).contains("certif") && lines.get(i).contains("that"))) {
            i++;
        }
        String subject = i + 1 < lines.size() ? lines.get(i + 1) : "";
        String iso = field(text, "Certificate No", "(ISO-\\d+)");
        if (iso != null) {
            Integer doc = document(ex, row, "iso_certificate", iso, lines.get(1) + " certificate (" + iso + ")",
                    "valid_until", field(text, "Valid until", "(.+)"));
            ex.fact(ex.addMention(EntityType.COMPANY, subject, "certified_company"), "HOLDS", doc);
            ex.fact(doc, "ISSUED_BY", ex.addMention(EntityType.COMPANY, orEmpty(field(text, "Certification Body", "(.+)")), "certification_body"));
            return true;
        }
        Matcher n = Pattern.compile("\\d+").matcher(stem(row.path));
        Matcher expiry = Pattern.compile("Expiry:\\s*(" + DATE + ")").matcher(text);
        Integer doc = document(ex, row, "training_certificate", n.find() ? "CERT-" + n.group() : null,
                titleCase(lines.get(0)) + " – " + subject, "expiry", expiry.find() ? expiry.group(1) : null);
        Integer holder = personWithOrg(ex, subject, "certificate_holder", "HR".equals(row.area) ? owner(ex) : null);
        ex.fact(holder, "HOLDS", doc);
        ex.fact(doc, "ISSUED_BY", ex.addMention(EntityType.COMPANY, orEmpty(field(text, "Training Provider", "(.+)")), "training_provider"));
        return true;
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char c : s.toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        return sb.toString();
    }
}
