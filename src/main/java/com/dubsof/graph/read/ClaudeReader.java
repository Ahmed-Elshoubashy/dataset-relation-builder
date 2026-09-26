package com.dubsof.graph.read;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.dubsof.graph.Config;
import com.dubsof.graph.ingest.FileKind;

import java.util.Arrays;
import java.util.Base64;

/** OCR with Claude's vision: the scan is sent as a PDF document or image block and transcribed. */
public class ClaudeReader implements TextReader {

    private static final String PROMPT = "Transcribe all text in this document image exactly as written.\n\n"
            + "Rules:\n"
            + "- One visual line of text per output line, reading top-to-bottom; when there are side-by-side blocks, "
            + "finish the left block before the right one.\n"
            + "- Keep \"Label: value\" pairs on a single line.\n"
            + "- For tables, output the header row and each data row on its own line with cells separated by \" | \".\n"
            + "- Copy numbers, codes, currency symbols and names character-for-character; do not correct typos.\n"
            + "- Output only the transcription, with no commentary. If there is no text, output nothing.";

    private final AnthropicClient client;
    private final String model;

    /** @param apiKey key typed into the UI for this run, or null to use ANTHROPIC_API_KEY. Kept in memory only. */
    public ClaudeReader(String apiKey) {
        if (apiKey == null && Config.apiKeyFromEnv() == null) {
            throw new ReaderUnavailableException("ANTHROPIC_API_KEY is not set");
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv().maxRetries(4)
                .apiKey(apiKey != null ? apiKey : Config.apiKeyFromEnv());
        this.client = builder.build();
        this.model = Config.CLAUDE_MODEL;
    }

    public OcrBackend backend() {
        return OcrBackend.CLAUDE;
    }

    public String read(byte[] data, FileKind kind, String filename) {
        String b64 = Base64.getEncoder().encodeToString(data);
        ContentBlockParam block;
        if (kind == FileKind.PDF) {
            block = ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                    .source(Base64PdfSource.builder().data(b64).build()).build());
        } else if (kind == FileKind.PNG || kind == FileKind.JPG) {
            block = ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .data(b64)
                            .mediaType(kind == FileKind.PNG ? Base64ImageSource.MediaType.IMAGE_PNG : Base64ImageSource.MediaType.IMAGE_JPEG)
                            .build())
                    .build());
        } else {
            throw new IllegalArgumentException("ClaudeReader cannot read kind=" + kind);
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                // if the request is declined, let the API retry it on its recommended fallback model
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessageOfBlockParams(Arrays.asList(block,
                        ContentBlockParam.ofText(TextBlockParam.builder().text(PROMPT).build())))
                .build();
        Message response = client.messages().create(params);
        if (response.stopReason().isPresent() && response.stopReason().get().equals(StopReason.REFUSAL)) {
            throw new IllegalStateException("transcription refused for " + filename);
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock b : response.content()) {
            if (b.isText()) {
                text.append(b.asText().text());
            }
        }
        return text.toString().trim();
    }
}
