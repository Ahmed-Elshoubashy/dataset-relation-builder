package com.dubsof.graph.api;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.models.ModelListParams;
import com.dubsof.graph.Config;
import com.dubsof.graph.pipeline.Pipeline;
import com.dubsof.graph.pipeline.Progress;
import com.dubsof.graph.read.TesseractReader;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The "Analyse dataset" dialog: pick a folder, pick an OCR backend, watch progress.
 * One analysis at a time, in a background thread. The API key typed into the UI is
 * passed straight to the OCR reader for that run and is never stored or logged.
 */
public class AnalysisApi {

    private final ApiServer server;

    // state of the current (or last) analysis, guarded by "this"
    private String state = "idle";   // idle | running | done | error
    private int step;
    private String stage;
    private String detail;
    private String error;
    private String dataRoot;
    private String ocr;
    private String startedAt;
    private String finishedAt;
    private Map<String, Object> result;
    private final List<String> log = new ArrayList<String>();

    AnalysisApi(ApiServer server) {
        this.server = server;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("state", state);
        s.put("step", step);
        s.put("stage", stage);
        s.put("detail", detail);
        s.put("error", error);
        s.put("data_root", dataRoot);
        s.put("ocr", ocr);
        s.put("started_at", startedAt);
        s.put("finished_at", finishedAt);
        s.put("result", result);
        s.put("log", new ArrayList<String>(log.subList(Math.max(0, log.size() - 12), log.size())));
        return s;
    }

    private synchronized void progress(int step, String stage, String detail) {
        this.step = step;
        this.stage = stage;
        this.detail = detail;
        String line = stage + ": " + detail;
        if (log.isEmpty() || !log.get(log.size() - 1).equals(line)) {
            log.add(line);
        }
    }

    public Map<String, Object> options() throws IOException {
        File root = Config.dataRoot;
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("default_root", root.isDirectory() ? root.getCanonicalPath() : "");
        o.put("browse_start", nearestDir(root).getPath());
        o.put("browse_root", Config.BROWSE_ROOT == null ? null : Config.BROWSE_ROOT.getPath());
        o.put("home", System.getProperty("user.home"));
        o.put("tesseract", TesseractReader.isInstalled());
        o.put("env_key", Config.apiKeyFromEnv() != null);
        o.put("claude_model", Config.CLAUDE_MODEL);
        return o;
    }

    // ------------------------------------------------------------------ folder browser

    private static boolean insideRoot(File f) {
        File root = Config.BROWSE_ROOT;
        if (root == null) {
            return true;
        }
        for (File p = f; p != null; p = p.getParentFile()) {
            if (p.equals(root)) {
                return true;
            }
        }
        return false;
    }

    /** The folder itself if it exists, else its closest existing parent, never above the shared folder. */
    private static File nearestDir(File f) throws IOException {
        File p = f.getAbsoluteFile().getCanonicalFile();
        if (!insideRoot(p)) {
            return Config.BROWSE_ROOT;
        }
        while (!p.isDirectory() && p.getParentFile() != null && !p.equals(Config.BROWSE_ROOT)) {
            p = p.getParentFile();
        }
        return p;
    }

    /** Folders inside {@code path} for the dataset picker. A missing path opens its nearest existing parent. */
    public Map<String, Object> listDirs(String path) throws IOException {
        File requested = path == null ? Config.dataRoot : expandHome(path);
        File p = nearestDir(requested);
        File[] entries = p.listFiles();
        if (entries == null) {
            throw new ApiServer.ApiException(403, "No permission to open " + p);
        }
        Arrays.sort(entries, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
            }
        });
        List<Map<String, Object>> dirs = new ArrayList<Map<String, Object>>();
        int files = 0;
        for (File e : entries) {
            if (e.getName().startsWith(".")) {
                continue;
            }
            if (e.isDirectory()) {
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("name", e.getName());
                d.put("path", e.getPath());
                dirs.add(d);
            } else if (e.isFile()) {
                files++;
            }
        }
        boolean atTop = p.getParentFile() == null || p.equals(Config.BROWSE_ROOT);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("path", p.getPath());
        out.put("parent", atTop ? null : p.getParent());
        out.put("dirs", dirs);
        out.put("files", files);
        out.put("missing", path != null && !p.equals(requested.getAbsoluteFile().getCanonicalFile()) ? requested.getPath() : null);
        out.put("root", Config.BROWSE_ROOT == null ? null : Config.BROWSE_ROOT.getPath());
        return out;
    }

    // ------------------------------------------------------------------ running an analysis

    public Map<String, Object> start(Map<?, ?> body) throws IOException {
        final File root = expandHome(body.get("data_root") == null ? "" : String.valueOf(body.get("data_root")));
        final String backend = body.get("ocr") == null ? "none" : String.valueOf(body.get("ocr"));
        String typedKey = body.get("api_key") == null ? "" : String.valueOf(body.get("api_key")).trim();
        final String key = typedKey.isEmpty() ? null : typedKey;

        if (!Arrays.asList("none", "tesseract", "claude").contains(backend)) {
            throw new ApiServer.ApiException(400, "Unknown OCR option: " + backend);
        }
        if (!insideRoot(root.getAbsoluteFile().getCanonicalFile())) {
            throw new ApiServer.ApiException(400, "Only folders inside " + Config.BROWSE_ROOT + " are shared with the app. "
                    + "Set DATASETS_DIR to a folder that contains " + root + " and restart.");
        }
        if (!root.isDirectory()) {
            throw new ApiServer.ApiException(400, "Folder not found: " + root);
        }
        String[] children = root.list();
        if (children == null || children.length == 0) {
            throw new ApiServer.ApiException(400, root + " is empty");
        }
        if (backend.equals("claude")) {
            if (key == null && Config.apiKeyFromEnv() == null) {
                throw new ApiServer.ApiException(400, "Enter an Anthropic API key to use Claude.");
            }
            checkKey(key != null ? key : Config.apiKeyFromEnv());
        }
        if (backend.equals("tesseract") && !TesseractReader.isInstalled()) {
            throw new ApiServer.ApiException(400, "Tesseract isn't installed. Install it with: brew install tesseract");
        }

        synchronized (this) {
            if (state.equals("running")) {
                throw new ApiServer.ApiException(409, "An analysis is already running.");
            }
            state = "running";
            step = 0;
            stage = null;
            detail = null;
            error = null;
            result = null;
            finishedAt = null;
            log.clear();
            dataRoot = root.getCanonicalPath();
            ocr = backend;
            startedAt = now();
        }
        Thread worker = new Thread(new Runnable() {
            public void run() {
                runAnalysis(root, backend, key);
            }
        }, "analysis");
        worker.setDaemon(true);
        worker.start();
        return status();
    }

    private void runAnalysis(File root, String backend, String key) {
        Progress progress = new Progress() {
            public void update(int step, String stage, String detail) {
                progress(step, stage, detail);
            }
        };
        try {
            Pipeline.Result out = Pipeline.build(root, Config.DB_FILE, backend, key, progress);
            progress(5, "relate", "Loading the new graph");
            server.installGraph(out.built);
            synchronized (this) {
                result = new LinkedHashMap<String, Object>();
                result.put("entities", out.entities);
                result.put("read", out.read);
                state = "done";
            }
            progress(5, "relate", "Analysis complete");
        } catch (Exception e) {
            e.printStackTrace();
            synchronized (this) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                state = "error";
            }
        } finally {
            synchronized (this) {
                finishedAt = now();
            }
        }
    }

    /** One free call to make sure the key works before a long analysis starts. */
    private static void checkKey(String key) {
        try {
            AnthropicOkHttpClient.builder().apiKey(key).maxRetries(0).build()
                    .models().list(ModelListParams.builder().limit(1L).build());
        } catch (UnauthorizedException e) {
            throw new ApiServer.ApiException(400, "Anthropic rejected this API key. Check it and try again.");
        } catch (PermissionDeniedException e) {
            throw new ApiServer.ApiException(400, "This API key doesn't have permission to use the API.");
        } catch (AnthropicIoException e) {
            throw new ApiServer.ApiException(400, "Couldn't reach the Anthropic API. Check your internet connection.");
        }
    }

    private static File expandHome(String path) {
        return path.startsWith("~") ? new File(System.getProperty("user.home") + path.substring(1)) : new File(path);
    }

    private static String now() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
}
