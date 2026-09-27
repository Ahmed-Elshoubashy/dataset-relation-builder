package com.dubsof.graph.dataset;

import com.dubsof.graph.Config;
import com.dubsof.graph.resolve.NameMatcher;
import com.dubsof.graph.util.Json;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The conventions of one dataset, from an optional profile.json (in the dataset's root folder, or the
 * file named by ERKG_PROFILE). Everything is optional; without a file the defaults below are used:
 * no folder layout (files simply get no folder hints), no job id format, broad lists of legal suffixes
 * and free e-mail providers.
 * <pre>
 * {
 *   "folderPatterns": ["Clients/{company}/{job_id:P-\\d+} {title}/**"],
 *   "jobIdPattern": "P-\\d+",
 *   "owner": null,
 *   "genericEmailDomains": ["gmail.com", "outlook.com"],
 *   "legalSuffixes": ["ltd", "inc", "gmbh"]
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
    /** Job (project) ids in text, e.g. "P-\d+"; null when the dataset has none. */
    public final Pattern jobIdPattern;
    /** The owner organisation, when the profile names it (overrides detection). */
    public final String owner;
    /** Company names compared with this dataset's legal suffixes and free e-mail providers. */
    public final NameMatcher names;

    public Profile(String source, List<FolderPattern> folderPatterns, Pattern jobIdPattern, String owner, NameMatcher names) {
        this.source = source;
        this.folderPatterns = folderPatterns;
        this.jobIdPattern = jobIdPattern;
        this.owner = owner;
        this.names = names;
    }

    public static Profile defaults() {
        return new Profile("defaults", new ArrayList<>(), null, null,
                new NameMatcher(DEFAULT_LEGAL_SUFFIXES, DEFAULT_GENERIC_DOMAINS));
    }

    /** The profile for a dataset: ERKG_PROFILE if set, else profile.json in the dataset root, else the defaults. */
    public static Profile forDataset(File dataRoot) throws IOException {
        if (Config.PROFILE != null) {
            return read(new File(Config.PROFILE));
        }
        File inRoot = new File(dataRoot, "profile.json");
        return inRoot.isFile() ? read(inRoot) : defaults();
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
        NameMatcher names = new NameMatcher(
                (List<String>) list(json.get("legalSuffixes"), DEFAULT_LEGAL_SUFFIXES),
                (List<String>) list(json.get("genericEmailDomains"), DEFAULT_GENERIC_DOMAINS));
        return new Profile(file.getPath(), folderPatterns, jobIdPattern, owner, names);
    }

    /**
     * What a file's folder says, from the first folder pattern that matches its path. With no match (or no
     * patterns) only the generic parts are set: the top folder, and the second-level folder as the category.
     */
    public FolderContext folderContext(String path) {
        String[] parts = path.split("/");
        for (FolderPattern pattern : folderPatterns) {
            FolderContext ctx = pattern.match(path);
            if (ctx != null) {
                ctx.area = parts[0];
                return ctx;
            }
        }
        FolderContext ctx = new FolderContext();
        ctx.area = parts.length > 1 ? parts[0] : null;
        ctx.category = parts.length > 2 ? parts[1] : null;
        return ctx;
    }

    private static List<?> list(Object value, List<?> fallback) {
        return value instanceof List ? (List<?>) value : fallback;
    }
}
