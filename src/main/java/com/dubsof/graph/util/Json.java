package com.dubsof.graph.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small wrapper around Jackson for the JSON stored in attrs columns and sent by the API. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("bad JSON: " + json, e);
        }
    }

    public static <T> T read(byte[] json, Class<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }
}
