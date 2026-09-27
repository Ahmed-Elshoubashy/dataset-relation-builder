package com.dubsof.graph.extract.parsers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentNumbersTest {

    private final DocumentNumbers numbers = new DocumentNumbers();

    @Test
    void numberedFileName() {
        FilenameDocument doc = numbers.fromFilename("INV-8002_Acme Corporation");
        assertEquals("INV-8002", doc.number);
        assertEquals("invoice", doc.docType);
        assertEquals("Acme Corporation", doc.company);
    }

    @Test
    void versionWordsAreNotACompany() {
        assertNull(numbers.fromFilename("QUO-5238_v2").company);
        assertNull(numbers.fromFilename("DWG-9296_RevB").company);
        assertNull(numbers.fromFilename("INV-8034 FINAL").company);
        assertEquals("quote", numbers.fromFilename("QUO-5238_v2").docType);
    }

    @Test
    void unnumberedOrUnknownFileNameIsAllNull() {
        for (String stem : new String[] {"meeting notes march", "HR-1042", "GB-40_Datasheet"}) {
            FilenameDocument doc = numbers.fromFilename(stem);
            assertNull(doc.number, stem);
            assertNull(doc.docType, stem);
            assertNull(doc.company, stem);
        }
    }

    @Test
    void labelsGiveNumbersInAnyFormatAndTheirType() {
        assertEquals(Arrays.asList("HR-1042 invoice", "SO-88341 order", "PO-3038 purchase_order", "QUO-5236 quote"),
                found(numbers.labelled("Invoice No: HR-1042. Order SO-88341, PO Number: PO-3038, Ref: QUO-5236")));
    }

    @Test
    void anUnlabelledCodeIsAReferenceOnlyWithAKnownPrefix() {
        assertEquals(Arrays.asList("INV-8034 invoice"), found(numbers.references("See INV-8034 and HR-1042.")));
        numbers.learn("Invoice No: HR-1040");
        assertEquals(Arrays.asList("INV-8034 invoice", "HR-1042 invoice"), found(numbers.references("See INV-8034 and HR-1042.")));
        assertEquals("HR-1043", numbers.fromFilename("HR-1043").number);
    }

    @Test
    void aBareRefTeachesNoPrefix() {
        numbers.learn("Ref: AB-1234");
        assertTrue(numbers.references("AB-1234").isEmpty());
    }

    @Test
    void profilePrefixesAddToTheDefaults() {
        DocumentNumbers withProfile = new DocumentNumbers(Collections.singletonMap("RFQ", "request_for_quote"), null);
        assertEquals(Arrays.asList("RFQ-2041 request_for_quote"), found(withProfile.references("as asked in RFQ-2041")));
        assertEquals("invoice", withProfile.typeOf("INV-1"));
    }

    @Test
    void standardsAndSheetsAreNotReferencesAlone() {
        assertTrue(numbers.references("certified to ISO-9001, see SPEC-1234").isEmpty());
    }

    @Test
    void productCodesSkipDocumentJobAndSerialNumbers() {
        assertEquals("PK-10", numbers.productCode("Pick Cell PK-10 1 unit"));
        assertNull(numbers.productCode("INV-8034 JOB-2023-0003 SN-4410"));
        numbers.learn("Sales Order No: SO-8834");
        assertNull(numbers.productCode("SO-8834"));
    }

    @Test
    void jobIdsAreNeverDocuments() {
        DocumentNumbers withJobs = new DocumentNumbers(Collections.<String, String>emptyMap(),
                Pattern.compile("\\b(?:P-\\d{4})\\b"));
        assertTrue(withJobs.labelled("Ref: P-2041").isEmpty());
        assertNull(withJobs.productCode("P-2041"));
    }

    private static List<String> found(List<DocumentNumbers.Found> found) {
        List<String> out = new ArrayList<>();
        for (DocumentNumbers.Found f : found) {
            out.add(f.number + " " + f.docType);
        }
        return out;
    }
}
