package com.dubsof.graph.read;

/** Creates the TextReader for a backend. */
public final class Readers {

    private Readers() {
    }

    /**
     * @param apiKey only used by Claude; null means "use ANTHROPIC_API_KEY"
     */
    public static TextReader create(OcrBackend backend, String apiKey) {
        switch (backend) {
            case CLAUDE:
                return new ClaudeReader(apiKey);
            case TESSERACT:
                return new TesseractReader();
            default:
                return new NullReader();
        }
    }
}
