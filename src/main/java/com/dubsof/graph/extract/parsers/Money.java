package com.dubsof.graph.extract.parsers;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amounts of money in documents: "£1,234.56", "$1,234.56", "USD 1,234.56", "1.234,56 €".
 * Both decimal styles are read: the separator before the last two digits is the decimal point.
 */
final class Money {

    /** Before an amount: an ISO code ("USD "), or one sign (£ $ € ¥, or the garbled '·' / '?' of some exports). */
    static final String BEFORE = "(?:[A-Z]{3}\\s?|[^\\d\\s|]\\s?)?";
    /** "1,234.56" or "1.234,56". */
    static final String AMOUNT = "(?:[\\d,]+\\.\\d{2}|[\\d.]+,\\d{2})";
    /** After an amount: " €", " EUR". */
    static final String AFTER = "(?:\\s?(?:[A-Z]{3}(?![A-Za-z])|[£$€¥]))?";

    /** A currency sign or ISO code next to a number. */
    private static final Pattern CURRENCY = Pattern.compile(
            "([£$€¥])\\s?\\d|\\d\\s?([£$€¥])|(?<![A-Za-z])(USD|EUR|GBP|JPY|CHF|CAD|AUD|NZD|SEK|NOK|DKK|PLN|CZK|INR|CNY|HKD|SGD|ZAR|MXN|BRL)(?![A-Za-z])");
    private static final Map<String, String> SIGNS = new HashMap<>();

    static {
        SIGNS.put("£", "GBP");
        SIGNS.put("$", "USD");
        SIGNS.put("€", "EUR");
        SIGNS.put("¥", "JPY");
    }

    private Money() {
    }

    /** "1,234.56" -> 1234.56, "1.234,56" -> 1234.56. */
    static double parse(String amount) {
        boolean decimalComma = amount.lastIndexOf(',') > amount.lastIndexOf('.');
        String plain = decimalComma ? amount.replace(".", "").replace(',', '.') : amount.replace(",", "");
        return Double.parseDouble(plain);
    }

    /** The ISO code of the first currency written next to an amount in this text, or null. "$" is taken as USD. */
    static String currency(String text) {
        Matcher m = CURRENCY.matcher(text);
        if (!m.find()) {
            return null;
        }
        String sign = m.group(1) != null ? m.group(1) : m.group(2);
        return sign != null ? SIGNS.get(sign) : m.group(3);
    }
}
