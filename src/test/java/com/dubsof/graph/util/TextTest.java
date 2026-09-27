package com.dubsof.graph.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    @Test
    void linesAreTrimmedAndBlankLinesDropped() {
        assertEquals(List.of("INVOICE", "No: INV-8034"), Text.lines("  INVOICE \r\n\n   \nNo: INV-8034\n"));
    }

    @Test
    void editDistanceCountsASwapAsOneEdit() {
        assertEquals(0, Text.damerauLevenshtein("blenheim", "blenheim"));
        assertEquals(1, Text.damerauLevenshtein("blenhiem", "blenheim"));   // swapped letters
        assertEquals(1, Text.damerauLevenshtein("kingsly", "kingsley"));    // missing letter
        assertEquals(2, Text.damerauLevenshtein("pharma", "farma"));        // p -> f, drop h
    }

    @Test
    void ratioIsHundredForEqualAndLowerForDifferent() {
        assertEquals(100.0, Text.ratio("shrink wrap retrofit", "shrink wrap retrofit"));
        assertTrue(Text.ratio("shrink wrap retrofit", "shrink-wrap retrofit") >= 90);
        assertTrue(Text.ratio("shrink wrap retrofit", "annual maintenance") < 60);
    }

    @Test
    void latin1IsUsedWhenBytesAreNotUtf8() {
        assertEquals("£10", Text.utf8OrLatin1("£10".getBytes(StandardCharsets.UTF_8)));
        assertEquals("£10", Text.utf8OrLatin1("£10".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void smallHelpers() {
        assertTrue(Text.isBlank(null));
        assertTrue(Text.isBlank(" \n\t"));
        assertFalse(Text.isBlank(" x "));
        assertEquals("ACME Corp", Text.collapseSpaces("  ACME \n  Corp "));
        assertEquals("INV-", Text.truncate("INV-8034", 4));
        assertEquals("INV", Text.truncate("INV", 10));
        assertEquals(64, Text.sha256(new byte[0]).length());
    }
}
