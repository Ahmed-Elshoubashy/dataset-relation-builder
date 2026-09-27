package com.dubsof.graph.dataset;

import com.dubsof.graph.Config;
import com.dubsof.graph.extract.parsers.DocumentNumbers;
import com.dubsof.graph.resolve.NameMatcher;
import com.dubsof.graph.util.Json;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The conventions of one dataset, from an optional profile file (see {@link #forDataset} for where it
 * comes from). Everything is optional; without a file the defaults below are used:
 * no folder layout (files simply get no folder hints), no job id format, broad lists of legal suffixes
 * and free e-mail providers.
 * <pre>
 * {
 *   "folderPatterns": ["Clients/{company}/{job_id:P-\\d+} {title}/**"],
 *   "skipDirectories": ["/Software", "node_modules"],
 *   "jobIdPattern": "P-\\d+",
 *   "owner": null,
 *   "ownerDomain": null,
 *   "genericEmailDomains": ["gmail.com", "outlook.com"],
 *   "legalSuffixes": ["ltd", "inc", "gmbh"],
 *   "documentPrefixes": {"HR": "invoice", "SO": "order"}
 * }
 * </pre>
 */
public final class Profile {

    /** Legal suffixes when a profile does not list them: common English, German, Dutch, French, Italian, Nordic, Asian forms. */
    public static final List<String> DEFAULT_LEGAL_SUFFIXES = Arrays.asList(
            "ltd", "lt", "limited", "inc", "incorporated", "co", "corp", "corporation", "plc", "llc", "llp", "lp",
            "company", "gmbh", "ag", "kg", "ug", "sa", "sarl", "sas", "bv", "nv", "srl", "spa", "oy", "pty", "pte",
            "bhd", "kk", "sro", "kft");
    /** Free e-mail providers when a profile does not list them. */
    public static final List<String> DEFAULT_GENERIC_DOMAINS = Arrays.asList(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "msn.com", "yahoo.com",
            "yahoo.co.uk", "ymail.com", "icloud.com", "me.com", "mac.com", "aol.com", "proton.me", "protonmail.com",
            "gmx.de", "gmx.net", "gmx.com", "web.de", "t-online.de", "mail.com", "zoho.com", "yandex.ru",
            "btinternet.com", "orange.fr", "free.fr", "libero.it", "qq.com", "163.com");

    /** Where the profile came from: a file path, or "defaults". */
    public final String source;
    /** Folder layouts, tried in order; the first that matches a file's path wins. */
    public final List<FolderPattern> folderPatterns;
    /** Folders whose files are recorded but never read ("/Software": source code, not business files). */
    public final List<DirectoryPattern> skipDirectories;
    /** Job (project) ids in text, e.g. "P-\d+"; null when the dataset has none. */
    public final Pattern jobIdPattern;
    /** The owner organisation, when the profile names it (overrides detection). */
    public final String owner;
    /** The owner's e-mail domain, when the profile gives it with the owner; null: detected, if it matches. */
    public final String ownerDomain;
    /** True for the dataset's own profile.json: its owner then beats the server's ERKG_OWNER. */
    public final boolean fromDataset;
    /** Company names compared with this dataset's legal suffixes and free e-mail providers. */
    public final NameMatcher names;
    /** Document-number prefixes -> document type ("HR" -> "invoice"), added to DocumentNumbers' defaults. */
    public final Map<String, String> documentPrefixes;

    public Profile(String source, boolean fromDataset, List<FolderPattern> folderPatterns, List<DirectoryPattern> skipDirectories,
                   Pattern jobIdPattern, String owner, String ownerDomain, NameMatcher names, Map<String, String> documentPrefixes) {
        this.source = source;
        this.fromDataset = fromDataset;
        this.folderPatterns = folderPatterns;
        this.skipDirectories = skipDirectories;
        this.jobIdPattern = jobIdPattern;
        this.owner = owner;
        this.ownerDomain = ownerDomain;
        this.names = names;
        this.documentPrefixes = documentPrefixes;
    }

    public static Profile defaults() {
        return new Profile("defaults", false, new ArrayList<>(), new ArrayList<>(), null, null, null,
                new NameMatcher(DEFAULT_LEGAL_SUFFIXES, DEFAULT_GENERIC_DOMAINS), new LinkedHashMap<>());
    }

    /** The same profile with another source text, and marked as the dataset's own or not. */
    private Profile withSource(String newSource, boolean isFromDataset) {
        return new Profile(newSource, isFromDataset, folderPatterns, skipDirectories, jobIdPattern, owner, ownerDomain, names,
                documentPrefixes);
    }

    /** A fresh document-number reader for one analysis: the defaults, this profile's prefixes and job ids. */
    public DocumentNumbers documentNumbers() {
        return new DocumentNumbers(documentPrefixes, jobIdPattern);
    }

    /** The dialog's choice for "no profile, only the defaults". */
    public static final String NONE = "none";
    /** A shipped profile is suggested when its folder patterns match at least this share of the dataset's files. */
    private static final double SUGGEST_MIN_SHARE = 0.3;

    /**
     * The profile for a dataset, first found wins:
     * <ol>
     *   <li>profile.json in the dataset's root: the dataset's own conventions always win;</li>
     *   <li>the profile chosen in the Analyse dialog ({@code chosen}: a name from {@link #available()}, or "none");</li>
     *   <li>ERKG_PROFILE, the server's default;</li>
     *   <li>a shipped profile whose folder patterns match most of the dataset's paths (john-doe's layout
     *       is recognised without setting anything);</li>
     *   <li>the defaults.</li>
     * </ol>
     *
     * @param chosen null or "" when nothing was chosen
     */
    public static Profile forDataset(File dataRoot, String chosen) throws IOException {
        File inRoot = new File(dataRoot, "profile.json");
        if (inRoot.isFile()) {
            Profile own = read(inRoot);
            return own.withSource(own.source, true);
        }
        if (chosen != null && !chosen.isEmpty()) {
            if (chosen.equals(NONE)) {
                return defaults();
            }
            if (!available().contains(chosen)) {
                throw new IllegalArgumentException("Unknown profile: " + chosen);
            }
            return read(new File(Config.PROFILES_DIR, chosen));
        }
        if (Config.PROFILE != null) {
            return read(new File(Config.PROFILE));
        }
        Profile suggested = suggest(dataRoot);
        return suggested != null ? suggested : defaults();
    }

    /** The shipped profiles ("john-doe.json", ...), sorted; empty when there is no profiles folder. */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        File[] files = Config.PROFILES_DIR.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    names.add(file.getName());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * The shipped profile whose folder patterns match the largest share of the dataset's file paths,
     * if that share is at least 30%; null otherwise. Only reads the folder listing, not the files.
     */
    static Profile suggest(File dataRoot) throws IOException {
        List<String> paths = relativePaths(dataRoot);
        Profile best = null;
        double bestShare = SUGGEST_MIN_SHARE;
        for (String name : available()) {
            Profile profile = read(new File(Config.PROFILES_DIR, name));
            if (profile.folderPatterns.isEmpty() || paths.isEmpty()) {
                continue;
            }
            int matching = 0;
            for (String path : paths) {
                if (profile.matchingPattern(path) != null) {
                    matching++;
                }
            }
            double share = matching / (double) paths.size();
            if (share >= bestShare) {
                best = profile.withSource(profile.source
                        + String.format(" (suggested: its folders match %.0f%% of the files)", share * 100), false);
                bestShare = share;
            }
        }
        return best;
    }

    /** Every file under the root, as "a/b/c.pdf" relative to it. */
    private static List<String> relativePaths(File dataRoot) throws IOException {
        final Path root = dataRoot.toPath();
        final List<String> paths = new ArrayList<>();
        if (!dataRoot.isDirectory()) {
            return paths;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(file -> paths.add(root.relativize(file).toString().replace(File.separatorChar, '/')));
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    public static Profile read(File file) throws IOException {
        Map<String, Object> json = Json.readMap(new String(Files.readAllBytes(file.toPath()), "UTF-8"));
        List<FolderPattern> folderPatterns = new ArrayList<>();
        String jobIdRegex = null;
        for (Object pattern : list(json.get("folderPatterns"), new ArrayList<>())) {
            FolderPattern folderPattern = new FolderPattern(String.valueOf(pattern));
            folderPatterns.add(folderPattern);
            if (jobIdRegex == null) {
                jobIdRegex = folderPattern.jobIdRegex;
            }
        }
        if (json.get("jobIdPattern") != null) {
            jobIdRegex = String.valueOf(json.get("jobIdPattern"));
        }
        Pattern jobIdPattern = jobIdRegex == null ? null : Pattern.compile("\\b(?:" + jobIdRegex + ")\\b");
        String owner = json.get("owner") == null ? null : String.valueOf(json.get("owner"));
        String ownerDomain = json.get("ownerDomain") == null ? null : String.valueOf(json.get("ownerDomain")).toLowerCase();
        NameMatcher names = new NameMatcher(
                (List<String>) list(json.get("legalSuffixes"), DEFAULT_LEGAL_SUFFIXES),
                (List<String>) list(json.get("genericEmailDomains"), DEFAULT_GENERIC_DOMAINS));
        Map<String, String> documentPrefixes = new LinkedHashMap<>();
        if (json.get("documentPrefixes") instanceof Map) {
            for (Map.Entry<?, ?> prefix : ((Map<?, ?>) json.get("documentPrefixes")).entrySet()) {
                documentPrefixes.put(String.valueOf(prefix.getKey()).toUpperCase(), String.valueOf(prefix.getValue()));
            }
        }
        List<DirectoryPattern> skipDirectories = new ArrayList<>();
        for (Object pattern : list(json.get("skipDirectories"), new ArrayList<>())) {
            skipDirectories.add(new DirectoryPattern(String.valueOf(pattern)));
        }
        return new Profile(file.getPath(), false, folderPatterns, skipDirectories, jobIdPattern, owner, ownerDomain, names,
                documentPrefixes);
    }

    /**
     * What a file's folder says, from the first folder pattern that matches its path. With no match (or no
     * patterns) only the generic parts are set: the top folder, and the second-level folder as the category.
     */
    public FolderContext folderContext(String path) {
        String[] parts = path.split("/");
        FolderContext ctx = matchingPattern(path);
        if (ctx != null) {
            ctx.area = parts[0];
            return ctx;
        }
        ctx = new FolderContext();
        ctx.area = parts.length > 1 ? parts[0] : null;
        ctx.category = parts.length > 2 ? parts[1] : null;
        return ctx;
    }

    /** True when the file ("Software/tools/build.sh") is inside a folder the profile says to skip. */
    public boolean skips(String path) {
        for (DirectoryPattern pattern : skipDirectories) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /** What the first matching folder pattern reads from the path, or null when none matches. */
    private FolderContext matchingPattern(String path) {
        for (FolderPattern pattern : folderPatterns) {
            FolderContext ctx = pattern.match(path);
            if (ctx != null) {
                return ctx;
            }
        }
        return null;
    }

    private static List<?> list(Object value, List<?> fallback) {
        return value instanceof List ? (List<?>) value : fallback;
    }
}
