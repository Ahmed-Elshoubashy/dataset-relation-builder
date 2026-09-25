package com.dubsof.graph.ingest;

import com.dubsof.graph.Config;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.pipeline.Progress;
import com.dubsof.graph.util.Text;

import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Stage 1: inventory every file, including zip members and email attachments.
 *
 * File type is sniffed from content, not the extension: several zip members and
 * Unsorted files have no extension, and a few ".pdf" files are really PNGs.
 */
public class Ingestor {

    public static final String MEMBER_SEP = "::";
    public static final Pattern JOB_DIR = Pattern.compile("^(JOB-\\d{4}-\\d{4})\\s+(.+)$");

    private static final Set<String> IGNORED_NAMES = new HashSet<String>(Arrays.asList(".DS_Store", "Thumbs.db", "desktop.ini"));
    private static final Set<String> CODE_EXTS = new HashSet<String>(Arrays.asList(
            ".py", ".js", ".ts", ".go", ".c", ".h", ".rs", ".java", ".css", ".html", ".yml",
            ".yaml", ".toml", ".json", ".xml", ".mod", ".sample", ".example", ".gitignore"));
    private static final Pattern EMAIL_HEADERS = Pattern.compile("^(?:[A-Za-z-]+: .*\\r?\\n){2,}");
    private static final Pattern EMAIL_KEY_HEADER = Pattern.compile("^(From|MIME-Version|Content-Type):", Pattern.MULTILINE);

    private final Connection conn;
    private int count;

    public Ingestor(Connection conn) {
        this.conn = conn;
    }

    /** Walks {@code root} and records every file. Returns the number of files recorded. */
    public static int run(Connection conn, File root, Progress progress) throws Exception {
        Ingestor ing = new Ingestor(conn);
        List<Path> paths = listFiles(root.toPath());
        for (int i = 0; i < paths.size(); i++) {
            Path p = paths.get(i);
            String rel = root.toPath().relativize(p).toString().replace(File.separatorChar, '/');
            ing.add(rel, Files.readAllBytes(p), p.toFile(), null, null);
            if (i % 250 == 0) {
                progress.update(1, "ingest", String.format("Scanned %,d files", ing.count));
            }
        }
        // Byte-identical duplicates point at their first copy, so each unique blob is read/OCR'd once.
        Db.update(conn, "UPDATE files SET duplicate_of = (SELECT MIN(f2.id) FROM files f2 WHERE f2.sha256 = files.sha256)"
                + " WHERE id != (SELECT MIN(f2.id) FROM files f2 WHERE f2.sha256 = files.sha256)");
        Db.commit(conn);
        return ing.count;
    }

    /** All regular files under root, in a stable order (component by component, like Python's sorted(rglob)). */
    private static List<Path> listFiles(Path root) throws IOException {
        final List<Path> out = new ArrayList<Path>();
        java.util.stream.Stream<Path> walk = Files.walk(root);
        try {
            for (Object o : walk.toArray()) {
                Path p = (Path) o;
                if (Files.isRegularFile(p) && !IGNORED_NAMES.contains(p.getFileName().toString())) {
                    out.add(p);
                }
            }
        } finally {
            walk.close();
        }
        Collections.sort(out, new Comparator<Path>() {
            public int compare(Path a, Path b) {
                int n = Math.min(a.getNameCount(), b.getNameCount());
                for (int i = 0; i < n; i++) {
                    int c = a.getName(i).toString().compareTo(b.getName(i).toString());
                    if (c != 0) {
                        return c;
                    }
                }
                return a.getNameCount() - b.getNameCount();
            }
        });
        return out;
    }

    /** Records one file (or archive member) and expands it if it is a container. */
    public void add(String rel, byte[] data, File blob, Long parentId, FolderContext ctx) throws Exception {
        String name = lastName(rel);
        if (IGNORED_NAMES.contains(name)) {
            return;
        }
        String sha = Text.sha256(data);
        String kind = sniff(data, rel);
        String status = "new";
        if (name.startsWith("~$")) {
            status = "skipped";   // Office lock file
        } else if (kind.equals("code") || kind.equals("media") || kind.equals("binary")
                || rel.contains("/.git/") || rel.startsWith("Software/")) {
            status = "skipped";
        }
        if (blob == null) {
            blob = saveBlob(data, sha, name);
        }
        if (ctx == null) {
            ctx = FolderContext.of(rel);
        }
        long id = Db.insert(conn, "INSERT OR IGNORE INTO files (path, parent_id, blob_path, sha256, size, ext, kind,"
                        + " area, folder_company, folder_job, folder_category, status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                rel, parentId, blob.getAbsolutePath(), sha, data.length, extension(name), kind,
                ctx.area, ctx.company, ctx.job, ctx.category, status);
        count++;
        if (id == 0) {
            return;
        }
        // Members inherit the container's folder context (an email filed under a job belongs to that job).
        if (kind.equals("zip")) {
            expandZip(rel, data, id, ctx);
        } else if (kind.equals("eml")) {
            expandEmail(rel, data, id, ctx);
        }
    }

    private void expandZip(String rel, byte[] data, long id, FolderContext ctx) throws Exception {
        List<Object[]> members;
        try {
            members = readZip(data, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badName) {
            members = readZip(data, Charset.forName("CP437"));
        }
        for (Object[] m : members) {
            add(rel + MEMBER_SEP + m[0], (byte[]) m[1], null, id, ctx);
        }
    }

    private static List<Object[]> readZip(byte[] data, Charset charset) throws IOException {
        List<Object[]> out = new ArrayList<Object[]>();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data), charset);
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory() && !e.getName().startsWith("__MACOSX")) {
                    out.add(new Object[] {e.getName(), Text.readAll(zin)});
                }
            }
        } finally {
            zin.close();
        }
        return out;
    }

    private void expandEmail(String rel, byte[] data, long id, FolderContext ctx) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data));
        List<Part> attachments = new ArrayList<Part>();
        collectAttachments(msg, attachments);
        for (Part part : attachments) {
            byte[] payload = Text.readAll(part.getInputStream());
            if (payload.length > 0) {
                String fname = part.getFileName() != null ? part.getFileName() : "attachment.bin";
                add(rel + MEMBER_SEP + fname, payload, null, id, ctx);
            }
        }
    }

    private static void collectAttachments(Part part, List<Part> out) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition()) || child.getFileName() != null) {
                    out.add(child);
                } else {
                    collectAttachments(child, out);
                }
            }
        }
    }

    // ------------------------------------------------------------------ type sniffing

    public static String sniff(byte[] data, String name) {
        byte[] head = Arrays.copyOf(data, Math.min(data.length, 4096));
        String ext = extension(lastName(name));
        if (startsWith(head, "%PDF")) {
            return "pdf";
        }
        if (startsWith(head, "PK")) {
            List<String> names = zipNames(data);
            if (names == null) {
                return "corrupt";
            }
            for (String n : names) {
                if (n.startsWith("word/")) {
                    return "docx";
                }
            }
            for (String n : names) {
                if (n.startsWith("xl/")) {
                    return "xlsx";
                }
            }
            return "zip";
        }
        if (head.length >= 4 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "png";
        }
        if (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8) {
            return "jpg";
        }
        if (startsWith(head, "{\\rtf")) {
            return "rtf";
        }
        if (startsWith(head, "ID3") || startsWith(head, "RIFF")
                || (head.length >= 8 && new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp"))
                || (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xfb)) {
            return "media";
        }
        if (CODE_EXTS.contains(ext) || name.contains("/.git/") || name.startsWith(".git")) {
            return "code";
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head)).toString();
        } catch (CharacterCodingException e) {
            return "binary";
        }
        if (text.startsWith("BEGIN:VCARD")) {
            return "vcf";
        }
        if (text.startsWith("BEGIN:VCALENDAR")) {
            return "ics";
        }
        if (EMAIL_HEADERS.matcher(text).find() && EMAIL_KEY_HEADER.matcher(text).find()) {
            return "eml";
        }
        return "text";
    }

    private static List<String> zipNames(byte[] data) {
        try {
            List<String> names = new ArrayList<String>();
            ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.ISO_8859_1);
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.add(e.getName());
            }
            zin.close();
            return names.isEmpty() ? null : names;
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        if (data.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** Stores bytes that only exist inside an archive or email, named by content hash. */
    private static File saveBlob(byte[] data, String sha, String name) throws IOException {
        Config.BLOB_DIR.mkdirs();
        String ext = extension(name);
        File f = new File(Config.BLOB_DIR, sha + (ext == null ? "" : Text.truncate(ext, 8)));
        if (!f.exists()) {
            Files.write(f.toPath(), data);
        }
        return f;
    }

    static String lastName(String rel) {
        String[] members = rel.split(Pattern.quote(MEMBER_SEP));
        String last = members[members.length - 1];
        return last.substring(last.lastIndexOf('/') + 1);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');   // like Python's Path.suffix: ".gitignore" has none
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot).toLowerCase() : null;
    }

    /** Customer / job / category read from the folder a file sits in. */
    public static class FolderContext {
        public String area;
        public String company;
        public String job;
        public String category;

        static FolderContext of(String rel) {
            String[] parts = rel.split(Pattern.quote(MEMBER_SEP))[0].split("/");
            FolderContext ctx = new FolderContext();
            ctx.area = parts.length > 1 ? parts[0] : null;
            if (parts[0].equals("Customers") && parts.length > 2) {
                ctx.company = parts[1];
                Matcher m = JOB_DIR.matcher(parts[2]);
                if (parts.length > 3 && m.matches()) {
                    ctx.job = parts[2];
                    if (parts.length > 4) {
                        ctx.category = parts[3];
                    }
                }
            } else if (parts.length > 2) {
                ctx.category = parts[1];
            }
            return ctx;
        }
    }

}
