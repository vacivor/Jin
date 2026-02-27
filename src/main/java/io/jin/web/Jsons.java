package io.jin.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Jsons {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsons() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response body as JSON", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> targetType) {
        try {
            return MAPPER.readValue(json, targetType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize request body as JSON", e);
        }
    }
}
