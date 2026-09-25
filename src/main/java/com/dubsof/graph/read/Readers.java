package com.dubsof.graph.read;

/** Creates the TextReader for a backend name. */
public final class Readers {

    private Readers() {
    }

    /**
     * @param name   "claude", "tesseract" or "none"
     * @param apiKey only used by Claude; null means "use ANTHROPIC_API_KEY"
     */
    public static TextReader create(String name, String apiKey) {
        if ("claude".equals(name)) {
            return new ClaudeReader(apiKey);
        }
        if ("tesseract".equals(name)) {
            return new TesseractReader();
        }
        if ("none".equals(name)) {
            return new NullReader();
        }
        throw new IllegalArgumentException("unknown OCR backend " + name);
    }
}
