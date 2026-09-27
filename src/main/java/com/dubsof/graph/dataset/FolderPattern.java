package com.dubsof.graph.dataset;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One folder layout from a profile, e.g. {@code "Clients/{company}/{job_id:P-\d+} {title}/{category}/**"}:
 * <ul>
 *   <li>{@code {company}}, {@code {title}}, {@code {category}}: one folder name (or part of one);</li>
 *   <li>{@code {job_id:REGEX}}: the part of a folder name matching REGEX (the same regex finds job ids in text);</li>
 *   <li>{@code **}: the rest of the path, at least the file name;</li>
 *   <li>a space: one or more spaces; anything else: itself.</li>
 * </ul>
 */
public final class FolderPattern {

    /** One whole placeholder: {name} or {name:REGEX}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(company|job_id|title|category)(?::(.+))?\\}");

    /** The pattern as written in the profile. */
    public final String text;
    /** The {job_id:REGEX} regex, or null. */
    public final String jobIdRegex;
    private final Pattern regex;

    public FolderPattern(String text) {
        this.text = text;
        StringBuilder regex = new StringBuilder("^");
        String jobIdRegex = null;
        int last = 0;
        int open = text.indexOf('{');
        while (open >= 0) {
            int close = matchingBrace(text, open);   // the job id regex has braces of its own: \d{4}
            Matcher m = PLACEHOLDER.matcher(text.substring(open, close + 1));
            if (!m.matches()) {
                throw new IllegalArgumentException("unknown placeholder " + text.substring(open, close + 1) + " in " + text);
            }
            regex.append(literal(text.substring(last, open)));
            String group = m.group(1).replace("_", "");   // Java group names cannot contain '_'
            String value = m.group(2) != null ? m.group(2) : "[^/]+";
            if (m.group(1).equals("job_id") && m.group(2) != null) {
                jobIdRegex = m.group(2);
            }
            regex.append("(?<").append(group).append(">").append(value).append(")");
            last = close + 1;
            open = text.indexOf('{', last);
        }
        regex.append(literal(text.substring(last))).append("$");
        this.jobIdRegex = jobIdRegex;
        this.regex = Pattern.compile(regex.toString());
    }

    /**
     * The folder context of a path relative to the dataset root ("Clients/Acme/P-12 Line/Invoices/x.pdf"),
     * or null when the path does not follow this layout.
     */
    public FolderContext match(String path) {
        Matcher m = regex.matcher(path);
        if (!m.matches()) {
            return null;
        }
        FolderContext ctx = new FolderContext();
        ctx.company = group(m, "company");
        ctx.jobTitle = group(m, "title");
        ctx.jobId = group(m, "jobid") != null ? group(m, "jobid") : ctx.jobTitle;
        ctx.category = group(m, "category");
        if (ctx.jobId != null) {
            // the whole folder name the job id (or title) sits in
            int start = m.start(group(m, "jobid") != null ? "jobid" : "title");
            int segmentStart = path.lastIndexOf('/', start) + 1;
            int segmentEnd = path.indexOf('/', start);
            ctx.job = path.substring(segmentStart, segmentEnd < 0 ? path.length() : segmentEnd);
        }
        return ctx;
    }

    /** The index of the '}' that closes the '{' at {@code open}, counting the braces inside it. */
    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                depth++;
            } else if (text.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("unclosed { in " + text);
    }

    /** "**" is the rest of the path, a space is any run of spaces, everything else is literal. */
    private static String literal(String part) {
        StringBuilder out = new StringBuilder();
        String[] pieces = part.split("\\*\\*", -1);
        for (int p = 0; p < pieces.length; p++) {
            if (p > 0) {
                out.append(".+");
            }
            String[] words = pieces[p].split(" ", -1);
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    out.append("\\s+");
                }
                if (!words[i].isEmpty()) {
                    out.append(Pattern.quote(words[i]));
                }
            }
        }
        return out.toString();
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException noSuchGroup) {
            return null;
        }
    }
}
