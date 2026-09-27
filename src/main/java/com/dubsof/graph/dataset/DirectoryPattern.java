package com.dubsof.graph.dataset;

import java.util.regex.Pattern;

/**
 * A folder to skip, from a profile's {@code skipDirectories}, written like a .gitignore line:
 * <ul>
 *   <li>{@code "/Software"}: the folder Software at the dataset's root only;</li>
 *   <li>{@code "node_modules"}: a folder with that name at any depth;</li>
 *   <li>{@code "Customers/*}{@code /Archive"}: a path from the root (a pattern with a '/' in it starts at the root);</li>
 *   <li>{@code *} any part of one folder name, {@code ?} one character, {@code **} any number of folders
 *       ({@code "**}{@code /backup"} is a backup folder at any depth). A trailing '/' is allowed.</li>
 * </ul>
 * Everything inside a matching folder is skipped, zip members included ("Software/tools.zip::a.pdf").
 */
public final class DirectoryPattern {

    /** The pattern as written in the profile. */
    public final String text;
    private final Pattern regex;

    public DirectoryPattern(String text) {
        this.text = text;
        String pattern = text.trim().replaceAll("/+$", "");
        if (pattern.isEmpty() || pattern.equals("/")) {
            throw new IllegalArgumentException("empty directory pattern: \"" + text + "\"");
        }
        // with a '/' it is a path from the root; a bare name can be any folder on the way
        boolean fromRoot = pattern.contains("/");
        pattern = pattern.replaceFirst("^/", "");
        this.regex = Pattern.compile((fromRoot ? "^" : "(?:^|.*/)") + globToRegex(pattern) + "/.*");
    }

    /** True when the file (a path relative to the dataset root, with '/' separators) is inside a matching folder. */
    public boolean matches(String path) {
        return regex.matcher(path).matches();
    }

    /** "Customers/*" -> "\QCustomers/\E[^/]*": ** crosses folders, * and ? stay inside one folder name. */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 2 < glob.length() && glob.charAt(i + 1) == '*' && glob.charAt(i + 2) == '/') {
                regex.append("(?:.*/)?");   // "**/backup": any folders, or none, then backup
                i += 2;
            } else if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
