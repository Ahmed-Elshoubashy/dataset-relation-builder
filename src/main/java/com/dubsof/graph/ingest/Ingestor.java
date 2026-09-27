package com.dubsof.graph.ingest;

import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.dataset.FolderContext;
import com.dubsof.graph.dataset.Profile;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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

    private static final Set<String> IGNORED_NAMES = new HashSet<String>(Arrays.asList(".DS_Store", "Thumbs.db", "desktop.ini"));
    private static final Set<String> CODE_EXTS = new HashSet<String>(Arrays.asList(
            ".py", ".js", ".ts", ".go", ".c", ".h", ".rs", ".java", ".css", ".html", ".yml",
            ".yaml", ".toml", ".json", ".xml", ".mod", ".sample", ".example", ".gitignore"));
    private static final Pattern EMAIL_HEADERS = Pattern.compile("^(?:[A-Za-z-]+: .*\\r?\\n){2,}");
    private static final Pattern EMAIL_KEY_HEADER = Pattern.compile("^(From|MIME-Version|Content-Type):", Pattern.MULTILINE);

    private final Connection conn;
    /** Where the bytes of zip members and e-mail attachments are stored (they have no file of their own). */
    private final File blobDir;
    /** Reads what each file's folder says (customer, project, category). */
    private final Profile profile;
    private final FilesDao filesDao = new FilesDao();
    private int count;

    public Ingestor(Connection conn, File blobDir, Profile profile) {
        this.conn = conn;
        this.blobDir = blobDir;
        this.profile = profile;
    }

    /** Walks {@code root} and records every file. Returns the number of files recorded. */
    public static int run(Connection conn, File root, File blobDir, Profile profile, Progress progress) throws Exception {
        Ingestor ing = new Ingestor(conn, blobDir, profile);
        List<Path> paths = listFiles(root.toPath());
        for (int i = 0; i < paths.size(); i++) {
            Path path = paths.get(i);
            String relativePath = relativePath(root.toPath(), path);
            ing.add(relativePath, Files.readAllBytes(path), path.toFile(), null, null);
            if (i % 250 == 0) {
                progress.update(1, "ingest", String.format("Scanned %,d files", ing.count));
            }
        }

        // Byte-identical duplicates point at their first copy, so each unique blob is read/OCR'd once.
        ing.filesDao.markDuplicates(conn);
        Db.commit(conn);
        return ing.count;
    }

    /**
     * All regular files under root, sorted by their path relative to root.
     * Files.walk() returns files in the filesystem's own order, which can differ between runs and
     * machines (macOS vs Docker); a fixed order makes the same folder always give the same graph
     * (same entity ids, same "first copy" of duplicates, same first-seen names).
     */
    private static List<Path> listFiles(Path root) throws IOException {
        final List<Path> filePaths = new ArrayList<>();

        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Object fileObject : walk.toArray()) {
                Path path = (Path) fileObject;
                if (Files.isRegularFile(path) && !IGNORED_NAMES.contains(path.getFileName().toString())) {
                    filePaths.add(path);
                }
            }
        }

        filePaths.sort(Comparator.comparing(path -> relativePath(root, path)));
        return filePaths;
    }

    /** "Sales/Invoices/INV-8002.pdf": relative to root, always with '/' separators. */
    private static String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    /** Records one file (or archive member) and expands it if it is a container. */
    public void add(String rel, byte[] data, File blob, Long parentId, FolderContext ctx) throws Exception {
        String name = lastName(rel);
        if (IGNORED_NAMES.contains(name)) {
            return;
        }
        String sha = Text.sha256(data);
        FileKind kind = sniff(data, rel);
        FileStatus status = FileStatus.NEW;

        if (name.startsWith("~$")) {
            status = FileStatus.SKIPPED;   // Office lock file

        } else if (kind.isIgnored() || rel.contains("/.git/") || rel.startsWith("Software/")) {
            status = FileStatus.SKIPPED;
        }

        if (blob == null) {
            blob = saveBlob(data, sha, name);
        }

        if (ctx == null) {
            ctx = profile.folderContext(rel);
        }

        FileRow row = new FileRow();
        row.path = rel;
        row.parentId = parentId;
        row.blobPath = blob.getAbsolutePath();
        row.sha256 = sha;
        row.size = data.length;
        row.ext = extension(name);
        row.kind = kind;
        row.area = ctx.area;
        row.folderCompany = ctx.company;
        row.folderJob = ctx.job;
        row.folderJobId = ctx.jobId;
        row.folderJobTitle = ctx.jobTitle;
        row.folderCategory = ctx.category;
        row.status = status;
        long id = filesDao.insertIfAbsent(conn, row);
        count++;

        if (id == 0) {
            return;
        }
        // Members inherit the container's folder context (an email filed under a job belongs to that job).
        if (kind == FileKind.ZIP) {
            expandZip(rel, data, id, ctx);

        } else if (kind == FileKind.EML) {
            expandEmail(rel, data, id, ctx);
        }
    }

    /** One file inside a zip: its path within the zip and its bytes. */
    private static final class ZipMember {
        final String name;
        final byte[] bytes;

        ZipMember(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private void expandZip(String rel, byte[] data, long id, FolderContext ctx) throws Exception {
        List<ZipMember> members;
        try {
            members = readZip(data, StandardCharsets.UTF_8);

        } catch (IllegalArgumentException badName) {
            members = readZip(data, Charset.forName("CP437"));
        }
        for (ZipMember member : members) {
            add(rel + MEMBER_SEP + member.name, member.bytes, null, id, ctx);
        }
    }

    private static List<ZipMember> readZip(byte[] data, Charset charset) throws IOException {
        List<ZipMember> files = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data), charset)) {
            ZipEntry zipEntry = zin.getNextEntry();
            while (zipEntry != null) {
                // Skip folders, and the hidden "__MACOSX/" folder that macOS's "Compress" adds to zips:
                // it only holds "._<name>" metadata copies of the real files, not documents.
                if (!zipEntry.isDirectory() && !zipEntry.getName().startsWith("__MACOSX")) {
                    files.add(new ZipMember(zipEntry.getName(), Text.readAll(zin)));
                }
                zipEntry = zin.getNextEntry();
            }
        }
        return files;
    }

    private void expandEmail(String rel, byte[] data, long id, FolderContext ctx) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data));
        List<Part> attachments = new ArrayList<>();
        collectAttachments(msg, attachments);
        for (Part part : attachments) {
            byte[] payload = Text.readAll(part.getInputStream());
            if (payload.length > 0) {
                String fname = part.getFileName() != null ? part.getFileName() : "attachment.bin";
                add(rel + MEMBER_SEP + fname, payload, null, id, ctx);
            }
        }
    }

    private static void collectAttachments(Part part, List<Part> attachments) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition()) || child.getFileName() != null) {
                    attachments.add(child);
                } else {
                    collectAttachments(child, attachments);
                }
            }
        }
    }

    // ------------------------------------------------------------------ type sniffing

    public static FileKind sniff(byte[] data, String name) {
        byte[] head = Arrays.copyOf(data, Math.min(data.length, 4096));
        String ext = extension(lastName(name));
        if (startsWith(head, "%PDF")) {
            return FileKind.PDF;
        }
        if (startsWith(head, "PK")) {
            List<String> names = zipNames(data);
            if (names == null) {
                return FileKind.CORRUPT;
            }
            for (String n : names) {
                if (n.startsWith("word/")) {
                    return FileKind.DOCX;
                }
            }
            for (String n : names) {
                if (n.startsWith("xl/")) {
                    return FileKind.XLSX;
                }
            }
            return FileKind.ZIP;
        }
        if (head.length >= 4 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return FileKind.PNG;
        }
        if (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8) {
            return FileKind.JPG;
        }
        if (startsWith(head, "{\\rtf")) {
            return FileKind.RTF;
        }
        if (startsWith(head, "ID3") || startsWith(head, "RIFF")
                || (head.length >= 8 && new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp"))
                || (head.length >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xfb)) {
            return FileKind.MEDIA;
        }
        if (CODE_EXTS.contains(ext) || name.contains("/.git/") || name.startsWith(".git")) {
            return FileKind.CODE;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head)).toString();
        } catch (CharacterCodingException e) {
            return FileKind.BINARY;
        }
        if (text.startsWith("BEGIN:VCARD")) {
            return FileKind.VCF;
        }
        if (text.startsWith("BEGIN:VCALENDAR")) {
            return FileKind.ICS;
        }
        if (EMAIL_HEADERS.matcher(text).find() && EMAIL_KEY_HEADER.matcher(text).find()) {
            return FileKind.EML;
        }
        return FileKind.TEXT;
    }

    private static List<String> zipNames(byte[] data) {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.ISO_8859_1)) {
            List<String> names = new ArrayList<>();
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.add(e.getName());
            }
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
    private File saveBlob(byte[] data, String sha, String name) throws IOException {
        blobDir.mkdirs();
        String ext = extension(name);
        File f = new File(blobDir, sha + (ext == null ? "" : Text.truncate(ext, 8)));
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
}
