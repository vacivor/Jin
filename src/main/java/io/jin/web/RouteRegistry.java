package io.jin.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class RouteRegistry {
    private final List<RouteEntry> routes = new CopyOnWriteArrayList<>();

    public void register(HttpMethod method, String pathTemplate, HttpHandler handler) {
        String normalized = normalizePath(pathTemplate);
        List<String> segments = splitSegments(normalized);
        int paramCount = 0;
        int exactCount = 0;
        int singleWildcardCount = 0;
        int multiWildcardCount = 0;
        for (String segment : segments) {
            if ("**".equals(segment)) {
                multiWildcardCount++;
            } else if ("*".equals(segment)) {
                singleWildcardCount++;
            } else if (isPathVariable(segment)) {
                paramCount++;
            } else {
                exactCount++;
            }
        }
        routes.add(new RouteEntry(
                method,
                normalized,
                segments,
                paramCount,
                exactCount,
                singleWildcardCount,
                multiWildcardCount,
                handler
        ));
    }

    public RouteMatch match(HttpMethod method, String path) {
        String normalizedPath = normalizePath(path);
        List<String> requestSegments = splitSegments(normalizedPath);
        RouteEntry best = null;
        Map<String, String> bestParams = null;

        for (RouteEntry route : routes) {
            if (route.method != method) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            if (!matchSegments(route.segments, requestSegments, 0, 0, params)) {
                continue;
            }

            if (best == null || isHigherPriority(route, best)) {
                best = route;
                bestParams = params;
            }
        }

        if (best == null) {
            return null;
        }
        return new RouteMatch(best.handler, bestParams == null ? Map.of() : Map.copyOf(bestParams));
    }

    private boolean isPathVariable(String segment) {
        return segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2;
    }

    private boolean matchSegments(
            List<String> routeSegments,
            List<String> requestSegments,
            int routeIndex,
            int requestIndex,
            Map<String, String> params
    ) {
        if (routeIndex == routeSegments.size()) {
            return requestIndex == requestSegments.size();
        }

        String routeSegment = routeSegments.get(routeIndex);
        if ("**".equals(routeSegment)) {
            if (routeIndex == routeSegments.size() - 1) {
                return true;
            }
            for (int i = requestIndex; i <= requestSegments.size(); i++) {
                Map<String, String> snapshot = new LinkedHashMap<>(params);
                if (matchSegments(routeSegments, requestSegments, routeIndex + 1, i, params)) {
                    return true;
                }
                params.clear();
                params.putAll(snapshot);
            }
            return false;
        }

        if (requestIndex >= requestSegments.size()) {
            return false;
        }

        String requestSegment = requestSegments.get(requestIndex);
        if ("*".equals(routeSegment)) {
            return matchSegments(routeSegments, requestSegments, routeIndex + 1, requestIndex + 1, params);
        }
        if (isPathVariable(routeSegment)) {
            params.put(pathVariableName(routeSegment), requestSegment);
            return matchSegments(routeSegments, requestSegments, routeIndex + 1, requestIndex + 1, params);
        }
        if (!routeSegment.equals(requestSegment)) {
            return false;
        }
        return matchSegments(routeSegments, requestSegments, routeIndex + 1, requestIndex + 1, params);
    }

    private boolean isHigherPriority(RouteEntry current, RouteEntry existing) {
        if (current.exactCount != existing.exactCount) {
            return current.exactCount > existing.exactCount;
        }
        if (current.multiWildcardCount != existing.multiWildcardCount) {
            return current.multiWildcardCount < existing.multiWildcardCount;
        }
        if (current.singleWildcardCount != existing.singleWildcardCount) {
            return current.singleWildcardCount < existing.singleWildcardCount;
        }
        if (current.paramCount != existing.paramCount) {
            return current.paramCount < existing.paramCount;
        }
        return current.segments.size() > existing.segments.size();
    }

    private String pathVariableName(String segment) {
        return segment.substring(1, segment.length() - 1);
    }

    private List<String> splitSegments(String path) {
        if ("/".equals(path)) {
            return Collections.emptyList();
        }
        String[] parts = path.substring(1).split("/");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static final class RouteEntry {
        private final HttpMethod method;
        private final String template;
        private final List<String> segments;
        private final int paramCount;
        private final int exactCount;
        private final int singleWildcardCount;
        private final int multiWildcardCount;
        private final HttpHandler handler;

        private RouteEntry(
                HttpMethod method,
                String template,
                List<String> segments,
                int paramCount,
                int exactCount,
                int singleWildcardCount,
                int multiWildcardCount,
                HttpHandler handler
        ) {
            this.method = method;
            this.template = template;
            this.segments = segments;
            this.paramCount = paramCount;
            this.exactCount = exactCount;
            this.singleWildcardCount = singleWildcardCount;
            this.multiWildcardCount = multiWildcardCount;
            this.handler = handler;
        }
    }

    public record RouteMatch(HttpHandler handler, Map<String, String> pathParams) {
    }
}
