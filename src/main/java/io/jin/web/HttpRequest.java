package io.jin.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record HttpRequest(
        HttpMethod method,
        String path,
        String query,
        Map<String, String> headers,
        String body,
        Map<String, String> pathParams
) {
    public HttpRequest(HttpMethod method, String path, String query, Map<String, String> headers, String body) {
        this(method, path, query, headers, body, Map.of());
    }

    public HttpRequest(HttpMethod method, String path, Map<String, String> headers, String body) {
        this(method, path, null, headers, body, Map.of());
    }

    public HttpRequest withPathParams(Map<String, String> pathParams) {
        return new HttpRequest(method, path, query, headers, body, pathParams == null ? Map.of() : Map.copyOf(pathParams));
    }

    public String header(String name) {
        if (headers == null || name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String pathParam(String name) {
        if (pathParams == null || name == null) {
            return null;
        }
        return pathParams.get(name);
    }

    public String queryParam(String name) {
        if (query == null || query.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            if (!name.equals(key)) {
                continue;
            }
            return parts.length == 2 ? decode(parts[1]) : "";
        }
        return null;
    }

    public Map<String, String> queryParams() {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length == 2 ? decode(parts[1]) : "";
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
