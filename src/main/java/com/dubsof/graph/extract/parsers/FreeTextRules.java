package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.RelationType;
import com.dubsof.graph.resolve.NameMatcher;
import com.dubsof.graph.util.Text;

import javax.mail.internet.InternetAddress;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The general extractor without an API key: rules that work on any business text, not one template.
 * <ul>
 *   <li>people in "From:" / "To:" / "Cc:" lines, with their e-mail address;</li>
 *   <li>people in signature blocks: a sign-off, the name, then a line naming their company or role;</li>
 *   <li>companies: capitalised names ending in a legal suffix ("Mueller GmbH", "Harbor Robotics Inc");</li>
 *   <li>document numbers after a label: "Invoice No: HR-1042", "Order SO-88341", "Ref: QUO-5238".</li>
 * </ul>
 * It finds less than Claude does (no names in plain prose), but never sends anything anywhere.
 */
final class FreeTextRules {

    private static final Pattern ADDRESS_LINE = Pattern.compile("^(?:From|To|Cc):\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern SIGNOFF = Pattern.compile(
            "^(?:Yours faithfully|Yours sincerely|Kind regards|Best regards|Warm regards|Regards|Many thanks|Thanks"
                    + "|Thank you|Best|Cheers|Sincerely|Mit freundlichen Grüßen|Viele Grüße|Beste Grüße|Cordialement"
                    + "|Saludos|Met vriendelijke groet)[,.!]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_NUMBER = Pattern.compile(
            "(?i:\\b(?:invoice|order|quote|quotation|contract|ref|reference|po|so|delivery note|credit note))"
                    + "\\s*(?i:no\\.?|number|nr\\.?|#)?\\s*[:#]?\\s*\\b([A-Z]{2,5}-\\d{2,}(?:-\\d+)*)\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    private FreeTextRules() {
    }

    static TextFindings find(String text, NameMatcher names) {
        TextFindings found = new TextFindings();

        for (Matcher m = ADDRESS_LINE.matcher(text); m.find(); ) {
            for (InternetAddress a : ParserUtils.addresses(m.group(1))) {
                String email = a.getAddress();
                if (email != null && email.contains("@")) {
                    String name = a.getPersonal() != null ? a.getPersonal() : ParserUtils.nameFromAddress(email);
                    found.add(EntityType.PERSON, name, email, null, null);
                }
            }
        }

        List<String> lines = Text.lines(text);
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (!SIGNOFF.matcher(lines.get(i)).matches() || ParserUtils.cleanPerson(lines.get(i + 1)) == null) {
                continue;
            }
            // "Dana Price" / "Sales Manager, Harbor Robotics Inc" / "+1 510 555 0100" / "dana@harbor..."
            String name = ParserUtils.cleanPerson(lines.get(i + 1));
            String organisation = null;
            String jobTitle = null;
            String email = null;
            for (int j = i + 2; j < Math.min(i + 5, lines.size()); j++) {
                List<String> companies = names.findCompanyNames(lines.get(j));
                if (organisation == null && !companies.isEmpty()) {
                    organisation = companies.get(0);
                    int comma = lines.get(j).indexOf(',');
                    jobTitle = comma > 0 ? lines.get(j).substring(0, comma).trim() : null;
                }
                Matcher address = EMAIL.matcher(lines.get(j));
                if (email == null && address.find()) {
                    email = address.group();
                }
            }
            found.add(EntityType.PERSON, name, email, organisation, jobTitle);
            if (organisation != null) {
                found.relations.add(new TextFindings.FoundRelation(name, RelationType.WORKS_FOR, organisation));
            }
        }

        for (String company : names.findCompanyNames(text)) {
            found.add(EntityType.COMPANY, company, null, null, null);
        }
        for (Matcher m = DOCUMENT_NUMBER.matcher(text); m.find(); ) {
            found.add(EntityType.DOCUMENT, m.group(1), null, null, null);
        }
        return found;
    }
}
