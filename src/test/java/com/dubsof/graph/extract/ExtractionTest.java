package com.dubsof.graph.extract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionTest {

    @Test
    void sameTypeSurfaceAndRoleIsOneMentionWithMergedAttrs() {
        Extraction ex = new Extraction(1);
        Integer first = ex.addMention(EntityType.COMPANY, "ACME Corp", MentionRole.BILL_TO, "address", "240 Priory Lane");
        Integer second = ex.addMention(EntityType.COMPANY, "ACME Corp", MentionRole.BILL_TO, "vat", "GB 1");
        assertEquals(first, second);
        assertEquals(1, ex.mentions.size());
        assertEquals("240 Priory Lane", ex.mentions.get(0).attrs.get("address"));
        assertEquals("GB 1", ex.mentions.get(0).attrs.get("vat"));
    }

    @Test
    void differentRoleIsAnotherMention() {
        Extraction ex = new Extraction(1);
        Integer billTo = ex.addMention(EntityType.COMPANY, "Acme Corporation", MentionRole.BILL_TO);
        Integer folder = ex.addMention(EntityType.COMPANY, "Acme Corporation", MentionRole.FOLDER);
        assertEquals(0, billTo);
        assertEquals(1, folder);
    }

    @Test
    void surfaceIsCleanedAndEmptyTextIsNoMention() {
        Extraction ex = new Extraction(1);
        ex.addMention(EntityType.COMPANY, "  ACME   Corp, ", MentionRole.BILL_TO);
        assertEquals("ACME Corp", ex.mentions.get(0).surface);
        assertNull(ex.addMention(EntityType.COMPANY, " ,; ", MentionRole.BILL_TO));
        assertEquals(1, ex.mentions.size());
    }

    @Test
    void nullAttrsAreSkippedAndConfidenceIsKept() {
        Extraction ex = new Extraction(1);
        ex.addMentionWithConfidence(EntityType.PROJECT, "Shrink Wrap Retrofit", MentionRole.DOC_JOB_FIELD, 0.9,
                "company_mention", null, "job_id", "JOB-2023-0003");
        Extraction.Mention mention = ex.mentions.get(0);
        assertEquals(0.9, mention.confidence);
        assertTrue(!mention.attrs.containsKey("company_mention"));
        assertEquals("JOB-2023-0003", mention.attrs.get("job_id"));
    }

    @Test
    void factsWithAMissingEndOrToItselfAreDropped() {
        Extraction ex = new Extraction(1);
        Integer doc = ex.addMention(EntityType.DOCUMENT, "INV-8034", MentionRole.SELF);
        Integer company = ex.addMention(EntityType.COMPANY, "ACME Corp", MentionRole.BILL_TO);
        ex.fact(doc, RelationType.ISSUED_TO, company);
        ex.fact(doc, RelationType.ISSUED_TO, null);
        ex.fact(null, RelationType.ISSUED_TO, company);
        ex.fact(doc, RelationType.REFERENCES, doc);
        assertEquals(1, ex.facts.size());
        assertEquals(RelationType.ISSUED_TO, ex.facts.get(0).rel);
    }
}
