package com.dubsof.graph.resolve;

import com.dubsof.graph.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises and compares names of companies and people, and says which rule made two names match.
 *
 * A company name is first turned into its words (lower case, no punctuation, no legal suffix):
 * "ACME Corp." and "Acme Corporation" both become [acme]. Two names are then compared word by word,
 * and each kind of real-world variation has its own named rule and score:
 * <pre>
 *   rule          example                                                    score
 *   normalized    'Acme Corp.'               ~ 'ACME Corp'                    1.00
 *   spacing       'Iron Bridge Automotive'   ~ 'Ironbridge Automotive'        0.97
 *   abbreviation  'Castlemead Log.'          ~ 'Castlemead Logistics'         0.90 per word
 *   expansion     'Sterling Pharmaceuticals' ~ 'Sterling Pharma'              0.85 per word
 *   typo          'Blenhiem Foods'           ~ 'Blenheim Foods'               0.85 per word (0.75 for 2 letters)
 *   truncation    'Falcon Aerospace'         ~ 'Falcon Aerospace Components'  -0.08 per missing word
 *   acronym       'BFG Ltd'                  ~ 'Blenheim Foods Group'         0.85
 *   email_domain  'falconaero.co.uk'         ~ 'Falcon Aerospace Components'  0.92
 * </pre>
 * The Resolver accepts a match at 0.80 or more (see {@link Resolver#ACCEPT}).
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    /** Words that only say what kind of company it is; dropped from the end of a name. */
    private static final Set<String> LEGAL_SUFFIXES = new HashSet<>(Arrays.asList(
            "ltd", "lt", "limited", "inc", "incorporated", "co", "corp", "corporation", "plc", "llc",
            "company", "gmbh", "sa", "sarl"));
    /** Words ignored everywhere in a company name. */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList("and", "the", "of"));
    /** Free e-mail providers: their domain says nothing about someone's employer. */
    public static final Set<String> GENERIC_DOMAINS = new HashSet<>(Arrays.asList(
            "gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com", "btinternet.com"));

    /** Penalty per candidate word the mention leaves out ("Falcon Aerospace" misses "Components"). */
    private static final double TRUNCATION_PENALTY_PER_WORD = 0.08;

    /** "R. Bianchi": one capital letter (optional dot), then a surname. */
    private static final Pattern INITIAL_AND_SURNAME = Pattern.compile("^([A-Z])\\.?\\s+([A-Z][\\w'-]+)$");

    /**
     * A similarity score (0..1) and the rule(s) that produced it. A name matched word by word can
     * need several rules at once ("Blenhiem Foods" ~ "Blenheim Foods Group" is typo + truncation).
     */
    public static class Match {

        /** The matching rules (see the table in the class comment). */
        public enum Method {
            /** Same words once case, punctuation and legal suffixes are removed. */
            NORMALIZED,
            /** Same letters, different spaces ("Iron Bridge" ~ "Ironbridge"). */
            SPACING,
            /** Initials of the candidate ("BFG" ~ "Blenheim Foods Group"). */
            ACRONYM,
            /** A word is a prefix of the candidate's word ("Log." ~ "Logistics"). */
            ABBREVIATION,
            /** The candidate's word is a prefix of the word ("Pharmaceuticals" ~ "Pharma"). */
            EXPANSION,
            /** One or two letters wrong ("Blenhiem" ~ "Blenheim"). */
            TYPO,
            /** The candidate has extra words at the end ("Falcon Aerospace" ~ "Falcon Aerospace Components"). */
            TRUNCATION,
            /** An e-mail domain made of the candidate's words ("falconaero.co.uk"). */
            EMAIL_DOMAIN,
            /** Word-level only: two words are identical. Never part of a company match's methods. */
            EXACT;

            /** The text stored in {@code mentions.method}. */
            public String value() {
                return name().toLowerCase();
            }
        }

        public final double score;
        /** Usually one rule; several when the words needed different rules. */
        public final Set<Method> methods;

        public Match(double score, Method method) {
            this(score, EnumSet.of(method));
        }

        public Match(double score, Set<Method> methods) {
            this.score = score;
            this.methods = methods;
        }

        /** True when this match came from exactly this one rule. */
        public boolean is(Method method) {
            return methods.size() == 1 && methods.contains(method);
        }

        /** The rule names joined in alphabetical order, e.g. "normalized" or "truncation+typo". */
        public String methodName() {
            List<String> names = new ArrayList<>();
            for (Method method : methods) {
                names.add(method.value());
            }
            Collections.sort(names);
            return join(names, "+");
        }

        public String toString() {
            return methodName() + " " + score;
        }
    }

    // ------------------------------------------------------------------ company names

    /**
     * "Redwood Timber &amp; Joinery Ltd" -> [redwood, timber, joinery] (with keepLegalSuffixes: [..., ltd]).
     * Lower case, "&amp;" read as "and", punctuation removed, stop words removed, and legal suffixes
     * removed from the end (but never the only word: "Company" stays [company]).
     */
    private static List<String> companyWords(String name, boolean keepLegalSuffixes) {
        String cleaned = name.toLowerCase().replace("&", " and ").replaceAll("[^a-z0-9 ]+", " ");
        List<String> words = new ArrayList<>();
        for (String word : cleaned.trim().split("\\s+")) {
            if (!word.isEmpty() && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        if (!keepLegalSuffixes) {
            while (words.size() > 1 && LEGAL_SUFFIXES.contains(words.get(words.size() - 1))) {
                words.remove(words.size() - 1);
            }
        }
        return words;
    }

    /** The key a company entity is stored under: "Acme Corporation" and "ACME Corp." both become "acme". */
    public static String companyKey(String name) {
        return join(companyWords(name, false), " ");
    }

    /**
     * Compares a company name found in a file (the mention) with a known company (the candidate).
     * Returns null when they do not match at all.
     *
     * @param truncated the mention is known to be cut off (e.g. a filename), so its last word may be any prefix
     */
    public static Match matchCompany(String mention, String candidate, boolean truncated) {
        List<String> mentionWords = companyWords(mention, false);
        List<String> candidateWords = companyWords(candidate, false);
        if (mentionWords.isEmpty() || candidateWords.isEmpty()) {
            return null;
        }

        // 1. the same words: "ACME Corp" ~ "Acme Corporation"
        if (mentionWords.equals(candidateWords)) {
            return new Match(1.0, Match.Method.NORMALIZED);
        }

        // 2. the same letters with different spaces: "Red Wood Timber" ~ "Redwood Timber"
        if (join(mentionWords, "").equals(join(candidateWords, ""))) {
            return new Match(0.97, Match.Method.SPACING);
        }

        // 3. one short word made of the candidate's initials: "BFG" ~ "Blenheim Foods Group"
        String firstMentionWord = mentionWords.get(0);
        if (mentionWords.size() == 1 && firstMentionWord.length() >= 2 && firstMentionWord.length() <= 5
                && candidateWords.size() >= 2) {
            StringBuilder initials = new StringBuilder();
            for (String word : candidateWords) {
                initials.append(word.charAt(0));
            }
            if (firstMentionWord.equals(initials.toString())) {
                return new Match(0.85, Match.Method.ACRONYM);
            }
        }

        // 4. word by word. Names that do not even start with the same letter are never the same company.
        if (firstMentionWord.charAt(0) != candidateWords.get(0).charAt(0)) {
            return null;
        }
        Alignment alignment = align(mentionWords, candidateWords, truncated);
        if (alignment == null) {
            return null;
        }
        double score = Math.round(alignment.score * 1000) / 1000.0;   // 3 decimals
        // every word matched exactly, only leftover legal words differed
        if (alignment.methods.isEmpty()) {
            return new Match(score, Match.Method.NORMALIZED);
        }
        return new Match(score, alignment.methods);
    }

    /** How the mention's words line up with the candidate's words: a score and the rules that were needed. */
    private static class Alignment {
        final double score;
        /** The rules the words needed; empty when every word was identical. */
        final Set<Match.Method> methods;

        Alignment(double score, Set<Match.Method> methods) {
            this.score = score;
            this.methods = methods;
        }
    }

    /**
     * Finds the best way to match every mention word to the start of the candidate's words, or null
     * when there is none. Works from the first word on: it matches the first word(s) of each side,
     * then aligns the rest the same way (recursion), and keeps the best-scoring option.
     */
    private static Alignment align(List<String> mentionWords, List<String> candidateWords, boolean truncated) {
        // All mention words are used. Candidate words left over mean the mention is a truncation.
        if (mentionWords.isEmpty()) {
            Set<Match.Method> methods = EnumSet.noneOf(Match.Method.class);
            if (!candidateWords.isEmpty()) {
                methods.add(Match.Method.TRUNCATION);
            }
            return new Alignment(1.0 - TRUNCATION_PENALTY_PER_WORD * candidateWords.size(), methods);
        }
        // Mention words are left but the candidate has none: the mention says more than the candidate.
        if (candidateWords.isEmpty()) {
            return null;
        }

        // How many words each side uses in this step:
        //   {1, 1} one word each                       'Logistics' ~ 'Log'
        //   {2, 1} two mention words as one word       'Iron Bridge' ~ 'Ironbridge'
        //   {1, 2} two candidate words as one word     'Ironbridge' ~ 'Iron Bridge'
        int[][] steps = {{1, 1}, {2, 1}, {1, 2}};
        Alignment best = null;
        for (int[] step : steps) {
            int mentionWordsUsed = step[0];
            int candidateWordsUsed = step[1];
            if (mentionWords.size() < mentionWordsUsed || candidateWords.size() < candidateWordsUsed) {
                continue;
            }
            String mentionPart = join(mentionWords.subList(0, mentionWordsUsed), "");
            String candidatePart = join(candidateWords.subList(0, candidateWordsUsed), "");
            boolean isLastMentionWord = mentionWordsUsed == mentionWords.size();
            Match wordMatch = matchWords(mentionPart, candidatePart, truncated && isLastMentionWord);
            if (wordMatch == null) {
                continue;
            }

            Match.Method rule = wordMatch.methods.iterator().next();   // a word comparison gives exactly one rule
            if (mentionWordsUsed != 1 || candidateWordsUsed != 1) {
                // words joined together must still be the same word ('Iron Bridge' ~ 'Ironbridge'),
                // otherwise 'Acme Robotics' would count as an expansion of 'Acme'
                if (rule != Match.Method.EXACT && rule != Match.Method.TYPO) {
                    continue;
                }
                if (rule == Match.Method.EXACT) {
                    rule = Match.Method.SPACING;
                }
            }

            Alignment rest = align(mentionWords.subList(mentionWordsUsed, mentionWords.size()),
                    candidateWords.subList(candidateWordsUsed, candidateWords.size()), truncated);
            if (rest == null) {
                continue;
            }

            // The score is the average over the mention words: this step counts once, the rest counts
            // once per mention word it covers. On the last mention word, the rest is only the
            // truncation penalty (if candidate words are left over), so it multiplies instead.
            int mentionWordsLeft = Math.max(mentionWords.size() - mentionWordsUsed, 0) + 1;
            double score;
            if (mentionWordsLeft > 1) {
                score = (wordMatch.score + rest.score * (mentionWordsLeft - 1)) / mentionWordsLeft;
            } else {
                score = rest.methods.isEmpty() ? wordMatch.score : wordMatch.score * rest.score;
            }

            Set<Match.Method> methods = rest.methods.isEmpty() ? EnumSet.noneOf(Match.Method.class) : EnumSet.copyOf(rest.methods);
            if (rule != Match.Method.EXACT) {
                methods.add(rule);
            }
            if (best == null || score > best.score) {
                best = new Alignment(score, methods);
            }
        }
        return best;
    }

    /**
     * Compares one word of the mention with one word of the candidate, or null when they differ too much.
     *
     * @param mentionWordIsCut the mention word is the cut-off end of a truncated name, so any prefix counts
     */
    private static Match matchWords(String mentionWord, String candidateWord, boolean mentionWordIsCut) {
        if (mentionWord.equals(candidateWord)) {
            return new Match(1.0, Match.Method.EXACT);
        }
        // 'log' ~ 'logistics' (at least 3 letters, unless the word was cut off)
        if ((mentionWord.length() >= 3 || mentionWordIsCut) && candidateWord.startsWith(mentionWord)) {
            return new Match(0.9, Match.Method.ABBREVIATION);
        }
        // 'pharmaceuticals' ~ 'pharma' (the candidate word has at least 4 letters)
        if (candidateWord.length() >= 4 && mentionWord.startsWith(candidateWord)) {
            return new Match(0.85, Match.Method.EXPANSION);
        }
        // one letter wrong, missing, extra or swapped in words of 5+ letters; two in words of 8+ letters
        int shorterLength = Math.min(mentionWord.length(), candidateWord.length());
        if (shorterLength >= 5 && Text.damerauLevenshtein(mentionWord, candidateWord) <= 1) {
            return new Match(0.85, Match.Method.TYPO);
        }
        if (shorterLength >= 8 && Text.damerauLevenshtein(mentionWord, candidateWord) <= 2) {
            return new Match(0.75, Match.Method.TYPO);
        }
        return null;
    }

    // ------------------------------------------------------------------ e-mail domains

    /**
     * Compares an e-mail domain with a company name. The part before the first dot must be the
     * company's full first word followed by the start of each next word, in order; legal words may be
     * skipped. 'ironbridgeauto.co.uk' ~ 'Ironbridge Automotive Ltd' ("ironbridge" + "auto").
     */
    public static Match matchDomain(String domain, String candidate) {
        String label = domain.toLowerCase().split("\\.")[0].replaceAll("[^a-z0-9]", "");
        List<String> candidateWords = companyWords(candidate, true);
        if (label.isEmpty() || candidateWords.isEmpty() || !label.startsWith(candidateWords.get(0))) {
            return null;
        }
        boolean matches = restOfLabelMatches(label, candidateWords.get(0).length(), candidateWords, 1);
        return matches ? new Match(0.92, Match.Method.EMAIL_DOMAIN) : null;
    }

    /**
     * True when the label from {@code position} on is made of prefixes (2+ letters) of the candidate
     * words from {@code wordIndex} on, in order. Tries the longest prefix first; a legal word may be skipped.
     */
    private static boolean restOfLabelMatches(String label, int position, List<String> candidateWords, int wordIndex) {
        if (position == label.length()) {
            return true;   // the whole label is used up
        }
        if (wordIndex >= candidateWords.size()) {
            return false;  // letters left but no words to explain them
        }
        String word = candidateWords.get(wordIndex);
        for (int prefixLength = Math.min(word.length(), label.length() - position); prefixLength > 1; prefixLength--) {
            boolean prefixFits = label.substring(position, position + prefixLength).equals(word.substring(0, prefixLength));
            if (prefixFits && restOfLabelMatches(label, position + prefixLength, candidateWords, wordIndex + 1)) {
                return true;
            }
        }
        return LEGAL_SUFFIXES.contains(word) && restOfLabelMatches(label, position, candidateWords, wordIndex + 1);
    }

    // ------------------------------------------------------------------ people

    /** "Thomas  Bianchi" / "thomas bianchi" -> "thomas bianchi": lower case, letters and spaces only. */
    public static String personKey(String name) {
        String cleaned = name.toLowerCase().replace('’', '\'').replaceAll("[^a-z' -]", "");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /** 'R. Bianchi' -> {"r", "bianchi"}; null for full names. */
    public static String[] personInitialForm(String name) {
        Matcher matcher = INITIAL_AND_SURNAME.matcher(name.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new String[] {matcher.group(1).toLowerCase(), matcher.group(2).toLowerCase()};
    }

    static String join(List<String> parts, String separator) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                joined.append(separator);
            }
            joined.append(parts.get(i));
        }
        return joined.toString();
    }
}
