package com.dubsof.graph.chat;

/** A question the chat cannot answer, with the HTTP status to report it with. */
public class ChatException extends Exception {
    public final int status;

    public ChatException(int status, String message) {
        super(message);
        this.status = status;
    }
}
