package com.dubsof.graph.read;

import com.dubsof.graph.ingest.FileKind;

/** No OCR: image-only files stay unread (their folder and filename are still used). */
public class NullReader implements TextReader {

    public OcrBackend backend() {
        return OcrBackend.NONE;
    }

    public String read(byte[] data, FileKind kind, String filename) {
        throw new ReaderUnavailableException("OCR disabled");
    }
}
