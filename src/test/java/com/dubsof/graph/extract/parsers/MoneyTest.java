package com.dubsof.graph.extract.parsers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MoneyTest {

    @Test
    void bothDecimalStyles() {
        assertEquals(1234.56, Money.parse("1,234.56"));
        assertEquals(1234.56, Money.parse("1.234,56"));
        assertEquals(350.0, Money.parse("350.00"));
        assertEquals(350.0, Money.parse("350,00"));
    }

    @Test
    void currencySignsAndCodes() {
        assertEquals("GBP", Money.currency("TOTAL: £4,250.00"));
        assertEquals("USD", Money.currency("TOTAL: $4,250.00"));
        assertEquals("EUR", Money.currency("Gesamt: 4.250,00 €"));
        assertEquals("EUR", Money.currency("Total EUR 4,250.00"));
        assertEquals("CHF", Money.currency("Total 4,250.00 CHF"));
    }

    @Test
    void garbledOrMissingSignHasNoCurrency() {
        assertNull(Money.currency("TOTAL: ·4,250.00"));
        assertNull(Money.currency("TOTAL: 4,250.00"));
    }
}
