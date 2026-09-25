package com.dubsof.graph.read;

/** No OCR: image-only files stay unread (their folder and filename are still used). */
public class NullReader implements TextReader {

    public String name() {
        return "none";
    }

    public String read(byte[] data, String kind, String filename) {
        throw new ReaderUnavailableException("OCR disabled");
    }
}
