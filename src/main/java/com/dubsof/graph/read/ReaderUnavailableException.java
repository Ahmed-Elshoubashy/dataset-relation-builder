package com.dubsof.graph.read;

/** The backend is not configured (no API key, tesseract not installed, OCR switched off). */
public class ReaderUnavailableException extends RuntimeException {

    public ReaderUnavailableException(String message) {
        super(message);
    }
}
