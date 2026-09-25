package com.dubsof.graph.resolve;

import com.dubsof.graph.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Name normalisation and explainable similarity for organisations and people.
 *
 * Company matching aligns the words of a mention against a candidate's words, so each
 * kind of real-world variation gets its own named rule:
 * <pre>
 *   normalized   'Acme Corp.'               ~ 'ACME Corp'                 (legal suffix / case / punctuation)
 *   spacing      'Iron Bridge Automotive'   ~ 'Ironbridge Automotive'
 *   abbreviation 'Castlemead Log.'          ~ 'Castlemead Logistics'
 *   expansion    'Sterling Pharmaceuticals' ~ 'Sterling Pharma'
 *   typo         'Blenhiem Foods'           ~ 'Blenheim Foods'            (Damerau-Levenshtein &lt;= 1)
 *   truncation   'Falcon Aerospace'         ~ 'Falcon Aerospace Components'
 *   acronym      'BFG Ltd'                  ~ 'Blenheim Foods Group'
 *   email_domain 'falconaero.co.uk'         ~ 'Falcon Aerospace Components'
 * </pre>
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    public static final Set<String> LEGAL = new HashSet<String>(Arrays.asList(
            "ltd", "lt", "limited", "inc", "incorporated", "co", "corp", "corporation", "plc", "llc",
            "company", "gmbh", "sa", "sarl"));
    private static final Set<String> STOP = new HashSet<String>(Arrays.asList("and", "the", "of"));
    public static final Set<String> GENERIC_DOMAINS = new HashSet<String>(Arrays.asList(
            "gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com", "btinternet.com"));

    /** A similarity score (0..1) and the name of the rule that produced it. */
    public static class Match {
        public final double score;
        public final String method;

        public Match(double score, String method) {
            this.score = score;
            this.method = method;
        }

        public String toString() {
            return method + " " + score;
        }
    }

    // ------------------------------------------------------------------ companies

    public static List<String> companyTokens(String name, boolean keepLegal) {
        String s = name.toLowerCase().replace("&", " and ").replaceAll("[^a-z0-9 ]+", " ");
        List<String> tokens = new ArrayList<String>();
        for (String t : s.trim().split("\\s+")) {
            if (!t.isEmpty() && !STOP.contains(t)) {
                tokens.add(t);
            }
        }
        if (!keepLegal) {
            while (tokens.size() > 1 && LEGAL.contains(tokens.get(tokens.size() - 1))) {
                tokens.remove(tokens.size() - 1);
            }
        }
        return tokens;
    }

    /** "Acme Corporation" and "ACME Corp." both become "acme". */
    public static String companyKey(String name) {
        return join(companyTokens(name, false), " ");
    }

    public static Match matchCompany(String mention, String candidate) {
        return matchCompany(mention, candidate, false);
    }

    /** @param truncated the mention is known to be cut off (e.g. a filename), so its last word may be any prefix */
    public static Match matchCompany(String mention, String candidate, boolean truncated) {
        List<String> m = companyTokens(mention, false);
        List<String> s = companyTokens(candidate, false);
        if (m.isEmpty() || s.isEmpty()) {
            return null;
        }
        if (m.equals(s)) {
            return new Match(1.0, "normalized");
        }
        if (join(m, "").equals(join(s, ""))) {
            return new Match(0.97, "spacing");
        }
        // acronym: 'BFG' -> Blenheim Foods Group
        if (m.size() == 1 && m.get(0).length() >= 2 && m.get(0).length() <= 5 && s.size() >= 2) {
            StringBuilder initials = new StringBuilder();
            for (String t : s) {
                initials.append(t.charAt(0));
            }
            if (m.get(0).equals(initials.toString())) {
                return new Match(0.85, "acronym");
            }
        }
        if (m.get(0).charAt(0) != s.get(0).charAt(0)) {
            return null;
        }
        Alignment a = align(m, s, truncated);
        if (a == null) {
            return null;
        }
        double score = Math.round(a.score * 1000) / 1000.0;
        return new Match(score, a.methods.isEmpty() ? "normalized" : join(new ArrayList<String>(a.methods), "+"));
    }

    private static class Alignment {
        final double score;
        final Set<String> methods;   // sorted, so "+"-joined names are stable

        Alignment(double score, Set<String> methods) {
            this.score = score;
            this.methods = methods;
        }
    }

    /** Best alignment of all mention words onto a prefix of the candidate words. */
    private static Alignment align(List<String> m, List<String> s, boolean truncated) {
        if (m.isEmpty()) {
            Set<String> methods = new TreeSet<String>();
            if (!s.isEmpty()) {
                methods.add("truncation");   // trailing candidate words were left out
            }
            return new Alignment(1.0 - 0.08 * s.size(), methods);
        }
        if (s.isEmpty()) {
            return null;
        }
        Alignment best = null;
        int[][] steps = {{1, 1}, {2, 1}, {1, 2}};   // one word each / two mention words glued / two candidate words glued
        for (int[] step : steps) {
            int dm = step[0];
            int ds = step[1];
            if (m.size() < dm || s.size() < ds) {
                continue;
            }
            String a = join(m.subList(0, dm), "");
            String b = join(s.subList(0, ds), "");
            Match token = tokenScore(a, b, truncated && dm == m.size());
            if (token == null) {
                continue;
            }
            String how = token.method;
            if (dm != 1 || ds != 1) {
                // re-spaced words must still be the same word ('Iron Bridge' ~ 'Ironbridge'),
                // otherwise 'Acme Robotics' would become an 'expansion' of 'Acme'
                if (!how.equals("exact") && !how.equals("typo")) {
                    continue;
                }
                how = how.equals("exact") ? "spacing" : how;
            }
            Alignment rest = align(m.subList(dm, m.size()), s.subList(ds, s.size()), truncated);
            if (rest == null) {
                continue;
            }
            int n = Math.max(m.size() - dm, 0) + 1;
            double score;
            if (n > 1) {
                score = (token.score + rest.score * (n - 1)) / n;
            } else {
                score = rest.methods.isEmpty() ? token.score : token.score * rest.score;
            }
            Set<String> methods = new TreeSet<String>(rest.methods);
            if (!how.equals("exact")) {
                methods.add(how);
            }
            if (best == null || score > best.score) {
                best = new Alignment(score, methods);
            }
        }
        return best;
    }

    /**
     * Compares one word of the mention with one word of the candidate.
     *
     * @param cut the mention word is the cut-off end of a truncated name: any prefix counts
     */
    private static Match tokenScore(String a, String b, boolean cut) {
        if (a.equals(b)) {
            return new Match(1.0, "exact");
        }
        if ((a.length() >= 3 || cut) && b.startsWith(a)) {
            return new Match(0.9, "abbreviation");
        }
        if (b.length() >= 4 && a.startsWith(b)) {
            return new Match(0.85, "expansion");
        }
        int shortest = Math.min(a.length(), b.length());
        if (shortest >= 5 && Text.damerauLevenshtein(a, b) <= 1) {
            return new Match(0.85, "typo");
        }
        if (shortest >= 8 && Text.damerauLevenshtein(a, b) <= 2) {
            return new Match(0.75, "typo");
        }
        return null;
    }

    /**
     * 'ironbridgeauto.co.uk' ~ 'Ironbridge Automotive Ltd': the domain label must be the full
     * first word followed by prefixes of the following words (legal words may be skipped).
     */
    public static Match matchDomain(String domain, String candidate) {
        String label = domain.toLowerCase().split("\\.")[0].replaceAll("[^a-z0-9]", "");
        List<String> tokens = companyTokens(candidate, true);
        if (label.isEmpty() || tokens.isEmpty() || !label.startsWith(tokens.get(0))) {
            return null;
        }
        return domainRest(label, tokens.get(0).length(), tokens, 1) ? new Match(0.92, "email_domain") : null;
    }

    private static boolean domainRest(String label, int pos, List<String> tokens, int j) {
        if (pos == label.length()) {
            return true;
        }
        if (j >= tokens.size()) {
            return false;
        }
        String t = tokens.get(j);
        for (int k = Math.min(t.length(), label.length() - pos); k > 1; k--) {
            if (label.substring(pos, pos + k).equals(t.substring(0, k)) && domainRest(label, pos + k, tokens, j + 1)) {
                return true;
            }
        }
        return LEGAL.contains(t) && domainRest(label, pos, tokens, j + 1);
    }

    // ------------------------------------------------------------------ people

    public static String personKey(String name) {
        String s = name.toLowerCase().replace('’', '\'').replaceAll("[^a-z' -]", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 'R. Bianchi' -> {"r", "bianchi"}; null for full names. */
    public static String[] personInitialForm(String name) {
        Matcher m = Pattern.compile("^([A-Z])\\.?\\s+([A-Z][\\w'-]+)$").matcher(name.trim());
        return m.matches() ? new String[] {m.group(1).toLowerCase(), m.group(2).toLowerCase()} : null;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
