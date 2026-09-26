package com.dubsof;

import com.dubsof.graph.Config;
import com.dubsof.graph.api.ApiServer;
import com.dubsof.graph.dao.MetaDao;
import com.dubsof.graph.db.Db;
import com.dubsof.graph.pipeline.Pipeline;
import com.dubsof.graph.pipeline.Progress;
import com.dubsof.graph.read.OcrBackend;
import com.dubsof.graph.read.TextStage;

import java.io.File;
import java.sql.Connection;

/**
 * Command-line entry point.
 * <pre>
 *   serve                                    start the web explorer on http://localhost:8765 (default)
 *   run [--data DIR] [--ocr none|tesseract|claude]   full analysis of a dataset
 *   rebuild                                  re-run extract/resolve/relate on files already read
 *   ocr [--ocr ...]                          retry OCR for files still waiting for it, then rebuild
 * </pre>
 */
public class Main {

    private static final MetaDao metaDao = new MetaDao();

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "serve";
        File data = Config.dataRoot;
        OcrBackend ocr = Config.OCR_BACKEND;
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (args[i].equals("--data")) {
                data = new File(args[i + 1]);
            } else if (args[i].equals("--ocr")) {
                ocr = OcrBackend.fromValue(args[i + 1]);
            }
        }

        if (command.equals("serve")) {
            new ApiServer(Config.PORT).start();
        } else if (command.equals("run")) {
            Pipeline.Result result = Pipeline.build(data, Config.DB_FILE, ocr, null, Progress.CONSOLE);
            Db.install(result.built, Config.DB_FILE);
            System.out.println("\nentities: " + result.entities);
        } else if (command.equals("rebuild") || command.equals("ocr")) {
            try (Connection conn = Db.open(Config.DB_FILE, false)) {
                Db.init(conn);
                if (command.equals("ocr")) {
                    TextStage.run(conn, ocr, null, Progress.CONSOLE);
                    metaDao.set(conn, "ocr_backend", ocr.value());
                }
                Pipeline.detectOwner(conn, Progress.CONSOLE);
                System.out.println("\nentities: " + Pipeline.graphStages(conn, Progress.CONSOLE));
            }
        } else {
            System.err.println("usage: serve | run [--data DIR] [--ocr none|tesseract|claude] | rebuild | ocr [--ocr ...]");
            System.exit(2);
        }
    }
}
