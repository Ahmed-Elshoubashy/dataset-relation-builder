package com.dubsof.graph.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Small string, hashing and stream helpers used across the pipeline. */
public final class Text {

    private Text() {
    }

    /** Non-empty, trimmed lines. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The part of {@code s} before the first {@code separator} match: "Item | Qty" -> "Item". Never fails: a line made
     * only of separators ("|", which OCR reads from table borders) gives "", where split(...)[0] would throw.
     */
    public static String firstField(String s, String separator) {
        String[] fields = s.split(separator);
        return fields.length == 0 ? "" : fields[0];
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String collapseSpaces(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Edit distance where swapping two neighbouring letters counts as one edit (optimal string alignment). */
    public static int damerauLevenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** Similarity 0..100 based on the longest common subsequence (same idea as rapidfuzz's ratio). */
    public static double ratio(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 100;
        }
        int[][] l = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                l[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? l[i - 1][j - 1] + 1 : Math.max(l[i - 1][j], l[i][j - 1]);
            }
        }
        return 200.0 * l[a.length()][b.length()] / (a.length() + b.length());
    }

    public static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : hash) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static String utf8OrLatin1(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return s.indexOf('�') >= 0 ? new String(data, StandardCharsets.ISO_8859_1) : s;
    }

    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
