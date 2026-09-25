package com.dubsof.graph.pipeline;

/** Receives progress messages from the pipeline (printed by the CLI, polled by the web UI). */
public interface Progress {

    void update(int step, String stage, String detail);

    /** Prints "[2/5 read] ..." lines. */
    Progress CONSOLE = new Progress() {
        public void update(int step, String stage, String detail) {
            System.out.println("[" + step + "/5 " + stage + "] " + detail);
        }
    };
}
