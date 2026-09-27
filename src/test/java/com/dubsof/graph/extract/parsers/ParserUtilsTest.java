package com.dubsof.graph.extract.parsers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParserUtilsTest {

    @Test
    void numberedFileName() {
        FilenameDocument doc = ParserUtils.filenameDoc("INV-8002_Acme Corporation");
        assertEquals("INV-8002", doc.number);
        assertEquals("invoice", doc.docType);
        assertEquals("Acme Corporation", doc.company);
    }

    @Test
    void versionWordsAreNotACompany() {
        assertNull(ParserUtils.filenameDoc("QUO-5238_v2").company);
        assertNull(ParserUtils.filenameDoc("DWG-9296_RevB").company);
        assertNull(ParserUtils.filenameDoc("INV-8034 FINAL").company);
        assertEquals("quote", ParserUtils.filenameDoc("QUO-5238_v2").docType);
    }

    @Test
    void unnumberedFileNameIsAllNull() {
        FilenameDocument doc = ParserUtils.filenameDoc("meeting notes march");
        assertNull(doc.number);
        assertNull(doc.docType);
        assertNull(doc.company);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Customers/Acme Corporation/Invoices/INV-8034_Acme Corporation.pdf | INV-8034_Acme Corporation",
        "Admin/Scans/Invoices_archive_023.zip::INV-8009_Redwood Timber & J.pdf | INV-8009_Redwood Timber & J",
        "Unsorted/no_extension | no_extension",
    })
    void stemIsTheFileNameWithoutFolderOrExtension(String path, String stem) {
        assertEquals(stem, ParserUtils.stem(path));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "QUO-5238 FINAL   | QUO-5238",
        "QUO-5238_v2      | QUO-5238",
        "Contract_Supply__2 | Contract_Supply",
        "site plan (1)    | site plan",
        "INV-8034         | INV-8034",
    })
    void baseStemDropsVersionSuffixes(String stem, String base) {
        assertEquals(base, ParserUtils.baseStem(stem));
    }

    @Test
    void fieldAtLineStartOrMidLine() {
        String text = "No: INV-8034\nCV6 4LP Date: 12 Oct 2023\nAttn:Thomas Bianchi";
        assertEquals("INV-8034", ParserUtils.field(text, "No", "(INV-\\d+)"));
        assertEquals("12 Oct 2023", ParserUtils.field(text, "Date", "(" + ParserUtils.DATE + ")"));
        assertEquals("Thomas Bianchi", ParserUtils.field(text, "Attn", "(.+)"));
        assertNull(ParserUtils.field(text, "Job", "(.+)"));
    }

    @Test
    void cleanPersonKeepsNamesOnly() {
        assertEquals("Thomas Bianchi", ParserUtils.cleanPerson("Mr. Thomas Bianchi"));
        assertEquals("Grace O'Neill", ParserUtils.cleanPerson("  Grace O'Neill_ "));
        assertEquals("José Müller", ParserUtils.cleanPerson("José Müller"));   // accented names are names too
        assertNull(ParserUtils.cleanPerson("thomas bianchi"));          // not capitalised
        assertNull(ParserUtils.cleanPerson("Accounts"));                // one word
        assertNull(ParserUtils.cleanPerson("See the attached invoice")); // a sentence
        assertNull(ParserUtils.cleanPerson(null));
    }
}
