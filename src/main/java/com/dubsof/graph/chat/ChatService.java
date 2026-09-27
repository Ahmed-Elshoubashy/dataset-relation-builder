package com.dubsof.graph.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.dubsof.graph.Config;
import com.dubsof.graph.util.Json;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers a chat question about the graph. With an API key, Claude reads the question and calls the read-only
 * {@link ChatTools} until it can answer (at most CHAT_MAX_TOOL_ROUNDS rounds); every number and name in its answer
 * comes from a tool. Without a key, or when Claude fails, {@link ChatRules} answers the question shapes it knows.
 * The conversation is kept by the browser and sent with each question, so the server holds no chat state.
 */
public class ChatService {

    /** Tells Claude what the graph is, how to find things, and how to write entity links. */
    private static final String SYSTEM = "You answer questions about a knowledge graph built from one company's file share "
            + "(PDFs, e-mails, spreadsheets). The graph has companies, people, projects, documents and products, and relations "
            + "between them, each backed by the files that state it.\n\n"
            + "Rules:\n"
            + "- Use the tools for every fact. Never guess a number or a name; if the tools don't have it, say so.\n"
            + "- Turn a name in the question into an entity id with find_entities first. If several entities match, "
            + "say which one you used, or ask.\n"
            + "- 'Customers' are the owner's customers: use find_entities with customers_only. Documents 'sent to' a company "
            + "are the ones ISSUED_TO it (list_related with direction 'in' and the document type, e.g. doc_type 'quote').\n"
            + "- For aliases, abbreviations or other names of an entity, use get_aliases.\n"
            + "- Write every entity you mention as [[id|name]], e.g. [[2|Acme Corporation]]: the user can click it. "
            + "Use the ids the tools returned.\n"
            + "- When the answer is about one main entity, call show_in_graph with it.\n"
            + "- Be brief. Give totals exactly as the tools report them; for long lists show the first items and say how many more.\n";

    private final Connection conn;
    private final ChatTools tools;
    private final AnthropicClient client;   // null: offline

    /**
     * @param apiKey the key typed in the chat, or null to use the server's ANTHROPIC_API_KEY; with neither, offline
     */
    public ChatService(Connection conn, String apiKey) {
        this(conn, client(apiKey != null ? apiKey : Config.apiKeyFromEnv()));
    }

    /** With this client (tests point it at a fake server); null: offline. */
    ChatService(Connection conn, AnthropicClient client) {
        this.conn = conn;
        this.tools = new ChatTools(conn);
        this.client = client;
    }

    private static AnthropicClient client(String apiKey) {
        return apiKey == null ? null
                : AnthropicOkHttpClient.builder().fromEnv().maxRetries(Config.CLAUDE_MAX_RETRIES).apiKey(apiKey).build();
    }

    /**
     * @param messages the conversation, oldest first: {"role": "user" | "assistant", "content": text}; the last one
     *                 is the question
     */
    public ChatAnswer answer(List<Map<String, Object>> messages) throws Exception {
        String question = messages.isEmpty() ? "" : String.valueOf(messages.get(messages.size() - 1).get("content"));
        if (client == null) {
            return new ChatRules(tools).answer(question);
        }
        try {
            return askClaude(messages);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            // fresh tools for the fallback, so the links Claude's half-finished answer collected are not mixed in
            ChatAnswer offline = new ChatRules(new ChatTools(conn)).answer(question);
            offline.notice = "Anthropic rejected the API key, so the offline rules answered. Check the key and try again.";
            return offline;
        } catch (Exception e) {
            ChatAnswer offline = new ChatRules(new ChatTools(conn)).answer(question);
            offline.notice = "Claude could not answer (" + e.getClass().getSimpleName() + "), so the offline rules did.";
            return offline;
        }
    }

    private ChatAnswer askClaude(List<Map<String, Object>> messages) throws Exception {
        ChatAnswer answer = new ChatAnswer();
        answer.engine = "claude";
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(Config.CLAUDE_MODEL)
                .maxTokens(Config.CHAT_MAX_OUTPUT_TOKENS)
                .system(SYSTEM)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (ChatTools.Definition tool : ChatTools.definitions()) {
            builder.addTool(toTool(tool));
        }
        for (Map<String, Object> message : messages) {
            String text = String.valueOf(message.get("content"));
            if ("assistant".equals(message.get("role"))) {
                builder.addAssistantMessage(text);
            } else {
                builder.addUserMessage(text);
            }
        }

        MessageCreateParams params = builder.build();
        StringBuilder text = new StringBuilder();
        for (int round = 0; round < Config.CHAT_MAX_TOOL_ROUNDS; round++) {
            Message response = client.messages().create(params);
            text.setLength(0);
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.isText()) {
                    text.append(block.asText().text());
                } else if (block.isToolUse()) {
                    results.add(runTool(block.asToolUse(), answer));
                }
            }
            if (results.isEmpty()) {
                break;   // no more tool calls: this is the answer
            }
            params = params.toBuilder().addMessage(response).addUserMessageOfBlockParams(results).build();
            if (round == Config.CHAT_MAX_TOOL_ROUNDS - 1) {
                answer.notice = "Stopped after " + Config.CHAT_MAX_TOOL_ROUNDS + " tool calls; the answer may be incomplete.";
            }
        }
        answer.answer = text.toString().trim();
        answer.entities.putAll(tools.seen());
        answer.focus = tools.focus();
        return answer;
    }

    /** Runs one tool Claude asked for, and wraps its result (JSON) for Claude. */
    @SuppressWarnings("unchecked")
    private ContentBlockParam runTool(ToolUseBlock call, ChatAnswer answer) {
        Map<String, Object> input = call._input().convert(Map.class);
        if (input == null) {
            input = new LinkedHashMap<>();
        }
        answer.usedTool(call.name(), input);
        Object result = tools.run(call.name(), input);
        boolean failed = result instanceof Map && ((Map<?, ?>) result).containsKey("error");
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(call.id())
                .content(Json.write(result))
                .isError(failed)
                .build());
    }

    private static Tool toTool(ChatTools.Definition definition) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Object> property : definition.properties.entrySet()) {
            properties.putAdditionalProperty(property.getKey(), JsonValue.from(property.getValue()));
        }
        return Tool.builder()
                .name(definition.name)
                .description(definition.description)
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(definition.required).build())
                .build();
    }
}
