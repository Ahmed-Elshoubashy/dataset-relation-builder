package com.dubsof.graph.pipeline;

import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.ingest.FileKind;
import com.dubsof.graph.ingest.FileStatus;
import com.dubsof.graph.read.TextSource;

import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out whose file share this is: the organisation printed at the top of most
 * generated PDFs (the letterhead) and the most common e-mail sender domain.
 */


// TODO, revist this class again
public final class OwnerDetector {

    private static final Pattern LEGAL = Pattern.compile("\\b(Ltd|Limited|Inc|LLC|plc|GmbH|Corp|Corporation|Company|Co\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("^From: .*?@([\\w.-]+)", Pattern.MULTILINE);

    private static final FilesDao filesDao = new FilesDao();

    private OwnerDetector() {
    }

    /** Returns {owner name or null, owner domain or null}. */
    public static String[] detect(Connection conn) throws Exception {
        Map<String, Integer> heads = new LinkedHashMap<String, Integer>();
        int pdfs = 0;
        for (FileRow f : filesDao.findTexts(conn, FileKind.PDF, FileStatus.OK, TextSource.NATIVE)) {
            for (String line : f.text.split("\\r?\\n")) {
                if (!line.trim().isEmpty()) {
                    count(heads, line.trim());
                    pdfs++;
                    break;
                }
            }
        }
        String name = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : heads.entrySet()) {
            if (e.getValue() > bestCount && e.getValue() >= Math.max(5, 0.1 * pdfs) && LEGAL.matcher(e.getKey()).find()) {
                name = e.getKey();
                bestCount = e.getValue();
            }
        }
        Map<String, Integer> domains = new HashMap<String, Integer>();
        for (FileRow f : filesDao.findTexts(conn, FileKind.EML, FileStatus.OK, null)) {
            Matcher m = FROM.matcher(f.text);
            if (m.find()) {
                count(domains, m.group(1).toLowerCase().replaceAll(">+$", ""));
            }
        }
        String domain = null;
        for (Map.Entry<String, Integer> e : domains.entrySet()) {
            if (domain == null || e.getValue() > domains.get(domain)) {
                domain = e.getKey();
            }
        }
        return new String[] {name, domain};
    }

    private static void count(Map<String, Integer> m, String key) {
        m.put(key, m.containsKey(key) ? m.get(key) + 1 : 1);
    }
}
