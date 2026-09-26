package com.dubsof.graph.pipeline;

/** Receives progress messages from the pipeline (polled by the web UI). */
public interface Progress {

    void update(int step, String stage, String detail);
}
