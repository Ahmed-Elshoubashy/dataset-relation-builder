package com.dubsof.graph.api;

import com.dubsof.graph.Config;
import com.dubsof.graph.dao.FilesDao;
import com.dubsof.graph.dao.row.FileRow;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.util.Json;
import com.dubsof.graph.util.Text;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP server for the explorer: the JSON API plus the static UI in resources/web.
 * Same endpoints and JSON as the Python version, so the same web page works with both.
 *
 * Each request opens its own SQLite connection under a read lock; installing a freshly
 * analysed graph takes the write lock, so no request ever sees a half-replaced database.
 */
public class ApiServer {

    private static final Pattern ENTITY = Pattern.compile("^/api/entities/(\\d+)$");
    private static final Pattern FILE = Pattern.compile("^/api/files/(\\d+)$");
    private static final Pattern FILE_RAW = Pattern.compile("^/api/files/(\\d+)/raw$");

    private final int port;
    private final FilesDao filesDao = new FilesDao();
    private final ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock();
    private final AnalysisApi analysis;

    /** An error returned to the browser as {"detail": "..."} with an HTTP status. */
    public static class ApiException extends RuntimeException {
        final int status;

        public ApiException(int status, String detail) {
            super(detail);
            this.status = status;
        }
    }

    public ApiServer(int port) {
        this.port = port;
        this.analysis = new AnalysisApi(this);
    }

    public void start() throws Exception {
        // a fresh checkout has no graph yet: serve an empty one until the first analysis
        Connection conn = Db.open(Config.DB_FILE, false);
        Db.init(conn);
        conn.close();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                try {
                    route(ex);
                } catch (ApiException e) {
                    sendJson(ex, e.status, detail(e.getMessage()));
                } catch (Exception e) {
                    e.printStackTrace();
                    sendJson(ex, 500, detail(e.getClass().getSimpleName() + ": " + e.getMessage()));
                } finally {
                    ex.close();
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Entity graph explorer: http://localhost:" + port);
    }

    private void route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = queryParams(ex.getRequestURI().getRawQuery());
        String method = ex.getRequestMethod();

        if (path.equals("/api/analysis") && method.equals("POST")) {
            Map<?, ?> body = Json.read(Text.readAll(ex.getRequestBody()), Map.class);
            sendJson(ex, 202, analysis.start(body));
            return;
        }
        if (path.equals("/api/analysis")) {
            sendJson(ex, 200, analysis.status());
            return;
        }
        if (path.equals("/api/analysis/options")) {
            sendJson(ex, 200, analysis.options());
            return;
        }
        if (path.equals("/api/fs")) {
            sendJson(ex, 200, analysis.listDirs(q.get("path")));
            return;
        }
        if (path.startsWith("/api/")) {
            Matcher raw = FILE_RAW.matcher(path);
            if (raw.matches()) {
                sendRawFile(ex, Long.parseLong(raw.group(1)));
                return;
            }
            sendJson(ex, 200, graphQuery(path, q));
            return;
        }
        sendStatic(ex, path);
    }

    /** Read-only graph queries, each on its own connection. */
    private Object graphQuery(String path, Map<String, String> q) throws Exception {
        dbLock.readLock().lock();
        Connection conn = Db.open(Config.DB_FILE, true);
        try {
            GraphApi api = new GraphApi(conn);
            Matcher entity = ENTITY.matcher(path);
            Matcher file = FILE.matcher(path);
            if (path.equals("/api/stats")) {
                return api.stats();
            }
            if (path.equals("/api/entities")) {
                return api.entities(q.get("type"), q.get("q"), q.get("doc_type"),
                        Math.min(intParam(q, "limit", 100), 1000), intParam(q, "offset", 0));
            }
            if (entity.matches()) {
                return api.entity(Long.parseLong(entity.group(1)));
            }
            if (path.equals("/api/graph")) {
                Long center = q.containsKey("center") ? Long.valueOf(q.get("center")) : null;
                int depth = Math.max(1, Math.min(intParam(q, "depth", 1), 2));
                return api.graph(center, depth, Math.min(intParam(q, "limit", 70), 600),
                        !"false".equals(q.get("derived")), q.get("types"));
            }
            if (path.equals("/api/issues")) {
                return api.issues(q.get("kind"), Math.min(intParam(q, "limit", 200), 2000));
            }
            if (path.equals("/api/aliases")) {
                return api.aliases(q.containsKey("q") ? q.get("q") : "");
            }
            if (file.matches()) {
                return api.file(Long.parseLong(file.group(1)));
            }
            throw new ApiException(404, "Not Found");
        } finally {
            conn.close();
            dbLock.readLock().unlock();
        }
    }

    /** Swaps a finished analysis in; waits until no request is reading the old graph. */
    void installGraph(File built) throws Exception {
        dbLock.writeLock().lock();
        try {
            Db.install(built, Config.DB_FILE);
        } finally {
            dbLock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ responses

    private void sendRawFile(HttpExchange ex, long id) throws Exception {
        FileRow file;
        dbLock.readLock().lock();
        Connection conn = Db.open(Config.DB_FILE, true);
        try {
            file = filesDao.findById(conn, id);
        } finally {
            conn.close();
            dbLock.readLock().unlock();
        }
        if (file == null) {
            throw new ApiException(404, "Not Found");
        }
        String type = file.kind.contentType();
        String[] members = file.path.split(Pattern.quote("::"));
        String name = members[members.length - 1].substring(members[members.length - 1].lastIndexOf('/') + 1);
        byte[] data = java.nio.file.Files.readAllBytes(new File(file.blobPath).toPath());
        ex.getResponseHeaders().set("Content-Disposition", "inline; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        send(ex, 200, type, data);
    }

    private void sendStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            throw new ApiException(404, "Not Found");
        }
        InputStream in = ApiServer.class.getResourceAsStream("/web" + path);
        if (in == null) {
            throw new ApiException(404, "Not Found");
        }
        byte[] data;
        try {
            data = Text.readAll(in);
        } finally {
            in.close();
        }
        String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8" : "application/octet-stream";
        send(ex, 200, type, data);
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, data.length);
        OutputStream out = ex.getResponseBody();
        out.write(data);
        out.close();
    }

    private static Map<String, Object> detail(String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("detail", message);
        return m;
    }

    private static Map<String, String> queryParams(String raw) throws UnsupportedEncodingException {
        Map<String, String> q = new HashMap<String, String>();
        if (raw == null) {
            return q;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                q.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        return q;
    }

    private static int intParam(Map<String, String> q, String name, int fallback) {
        return q.containsKey(name) ? Integer.parseInt(q.get(name)) : fallback;
    }
}
