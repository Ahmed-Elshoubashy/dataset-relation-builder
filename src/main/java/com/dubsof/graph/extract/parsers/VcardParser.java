package com.dubsof.graph.extract.parsers;

import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.extract.EntityType;
import com.dubsof.graph.extract.Extraction;
import com.dubsof.graph.ingest.FileKind;

import static com.dubsof.graph.extract.parsers.ParserUtils.document;
import static com.dubsof.graph.extract.parsers.ParserUtils.field;
import static com.dubsof.graph.extract.parsers.ParserUtils.personWithOrg;
import static com.dubsof.graph.extract.parsers.ParserUtils.companyFromDomain;
import static com.dubsof.graph.extract.parsers.ParserUtils.orEmpty;

/** Contact cards (.vcf): one person and their organisation. */
public class VcardParser implements Parser {

    public boolean parse(Extraction ex, FileRow row, String text, Integer folderCompany, Integer project) {
        if (row.kind != FileKind.VCF) {
            return false;
        }
        String fn = field(text, "FN", "(.+)");
        String org = field(text, "ORG", "(.+)");
        String mail = field(text, "EMAIL", "(.+)");
        String id = (mail != null ? mail : fn != null ? fn : "").toLowerCase();
        Integer doc = document(ex, row, "contact_card", "vcard:" + id, "Contact card: " + fn);
        Integer orgMention = ex.addMention(EntityType.COMPANY, orEmpty(org), "vcard_org");
        Integer p = personWithOrg(ex, orEmpty(fn), "vcard", orgMention,
                "email", mail != null ? mail.toLowerCase() : null, "phone", field(text, "TEL", "(.+)"));
        if (mail != null) {
            ex.fact(p, "WORKS_FOR", companyFromDomain(ex, mail));
        }
        ex.fact(doc, "DESCRIBES", p);
        return true;
    }
}
