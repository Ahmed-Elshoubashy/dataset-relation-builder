package com.dubsof;

import com.dubsof.graph.Config;
import com.dubsof.graph.api.ApiServer;

/** Starts the web explorer on http://localhost:8765 (PORT); analyses are started from the UI. */
public class Main {

    public static void main(String[] args) throws Exception {
        new ApiServer(Config.PORT).start();
    }
}
