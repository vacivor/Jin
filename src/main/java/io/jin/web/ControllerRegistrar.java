package io.jin.web;

import io.jin.context.ApplicationContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ControllerRegistrar {
    private final ApplicationContext context;
    private final RouteRegistry routeRegistry;

    public ControllerRegistrar(ApplicationContext context, RouteRegistry routeRegistry) {
        this.context = context;
        this.routeRegistry = routeRegistry;
    }

    public void registerControllers(Class<?>... sources) {
        for (Class<?> source : sources) {
            if (!source.isAnnotationPresent(Path.class)) {
                continue;
            }
            Object controller = context.getBean(source);
            String prefix = source.getAnnotation(Path.class).value();

            for (Method method : source.getDeclaredMethods()) {
                RouteMapping route = resolveMapping(source, method);
                if (route == null) {
                    continue;
                }

                String fullPath = normalizePath(prefix + "/" + route.path());
                routeRegistry.register(route.method(), fullPath, request -> {
                    if (!isConsumesMatched(request, route.consumes())) {
                        return HttpResponse.of(415, "Unsupported Media Type");
                    }

                    String negotiated = negotiateProducedType(request, route.produces());
                    if (route.produces().length > 0 && negotiated == null) {
                        return HttpResponse.of(406, "Not Acceptable");
                    }

                    HttpResponse response = invokeControllerMethod(controller, method, request, route);
                    ensureContentType(response, route.produces(), negotiated);
                    return response;
                });
            }
        }
    }

    private RouteMapping resolveMapping(Class<?> source, Method method) {
        Path methodPath = method.getAnnotation(Path.class);
        String path = methodPath == null ? "" : methodPath.value();
        String[] consumes = resolveConsumes(source, method);
        String[] produces = resolveProduces(source, method);

        for (Annotation annotation : method.getAnnotations()) {
            jakarta.ws.rs.HttpMethod meta = annotation.annotationType().getAnnotation(jakarta.ws.rs.HttpMethod.class);
            if (meta == null) {
                continue;
            }
            HttpMethod resolved = HttpMethod.fromToken(meta.value());
            if (resolved != null) {
                return new RouteMapping(resolved, path, consumes, produces);
            }
        }
        return null;
    }

    private HttpResponse invokeControllerMethod(Object controller, Method method, HttpRequest request, RouteMapping route) {
        try {
            method.setAccessible(true);
            Object[] args = resolveMethodArgs(method, request, route);
            Object result = method.invoke(controller, args);
            return mapResult(result);
        } catch (BadRequestException e) {
            return HttpResponse.of(400, e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String detail = cause.getClass().getSimpleName() + ": " + (cause.getMessage() == null ? "" : cause.getMessage());
            return HttpResponse.of(500, "Internal Server Error: " + detail);
        } catch (IllegalAccessException e) {
            return HttpResponse.of(500, "Internal Server Error: " + e.getMessage());
        }
    }

    private Object[] resolveMethodArgs(Method method, HttpRequest request, RouteMapping route) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        int entityParamIndex = -1;

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (parameter.getType() == HttpRequest.class) {
                args[i] = request;
                continue;
            }

            PathParam pathParam = parameter.getAnnotation(PathParam.class);
            if (pathParam != null) {
                args[i] = bindParameter("path", pathParam.value(), parameter.getType(), request.pathParam(pathParam.value()));
                continue;
            }

            QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
            if (queryParam != null) {
                args[i] = bindParameter("query", queryParam.value(), parameter.getType(), request.queryParam(queryParam.value()));
                continue;
            }

            if (entityParamIndex != -1) {
                throw new BadRequestException("Only one request body parameter is supported");
            }
            entityParamIndex = i;
        }

        if (entityParamIndex != -1) {
            Parameter parameter = parameters[entityParamIndex];
            args[entityParamIndex] = bindEntityParameter(parameter.getType(), request, route);
        }
        return args;
    }

    private Object bindEntityParameter(Class<?> targetType, HttpRequest request, RouteMapping route) {
        String body = request.body();
        if (body == null || body.isBlank()) {
            if (targetType.isPrimitive()) {
                throw new BadRequestException("Missing request body");
            }
            return null;
        }

        String contentType = mediaTypeOnly(request.header("Content-Type"));
        boolean expectsJson = isJsonExpected(route.consumes(), contentType);
        try {
            if (expectsJson) {
                if (targetType == String.class) {
                    return body;
                }
                return Jsons.fromJson(body, targetType);
            }
            if (targetType == String.class) {
                return body;
            }
            return convert(body, targetType);
        } catch (RuntimeException e) {
            throw new BadRequestException("Invalid request body");
        }
    }

    private Object bindParameter(String source, String name, Class<?> targetType, String rawValue) {
        if (rawValue == null) {
            if (targetType.isPrimitive()) {
                throw new BadRequestException("Missing required " + source + " parameter: " + name);
            }
            return null;
        }

        try {
            return convert(rawValue, targetType);
        } catch (RuntimeException e) {
            throw new BadRequestException("Invalid " + source + " parameter: " + name + "=" + rawValue);
        }
    }

    private Object convert(String raw, Class<?> targetType) {
        if (targetType == String.class) {
            return raw;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(raw);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(raw);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(raw);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(raw);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(raw);
        }
        if (targetType == short.class || targetType == Short.class) {
            return Short.parseShort(raw);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.parseByte(raw);
        }
        if (targetType.isEnum()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object enumValue = Enum.valueOf((Class<? extends Enum>) targetType, raw);
            return enumValue;
        }
        throw new IllegalArgumentException("Unsupported parameter type: " + targetType.getName());
    }

    private HttpResponse mapResult(Object result) {
        if (result instanceof HttpResponse response) {
            return response;
        }
        if (result instanceof Response response) {
            return fromJakartaResponse(response);
        }
        if (result == null || result instanceof String || result instanceof Number || result instanceof Boolean) {
            return HttpResponse.ok(result == null ? "" : result.toString());
        }
        return HttpResponse.ok(Jsons.toJson(result)).header("Content-Type", "application/json; charset=UTF-8");
    }

    private HttpResponse fromJakartaResponse(Response response) {
        Object entity = response.getEntity();
        String body;
        boolean json = false;
        if (entity == null || entity instanceof String || entity instanceof Number || entity instanceof Boolean) {
            body = entity == null ? "" : entity.toString();
        } else {
            body = Jsons.toJson(entity);
            json = true;
        }

        HttpResponse httpResponse = HttpResponse.of(response.getStatus(), body);
        for (Map.Entry<String, List<Object>> entry : response.getHeaders().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            String value = entry.getValue().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
            httpResponse.header(entry.getKey(), value);
        }
        if (response.getMediaType() != null && !hasContentType(httpResponse)) {
            httpResponse.header("Content-Type", response.getMediaType().toString());
        }
        if (json && !hasContentType(httpResponse)) {
            httpResponse.header("Content-Type", "application/json; charset=UTF-8");
        }
        return httpResponse;
    }

    private String[] resolveConsumes(Class<?> source, Method method) {
        Consumes methodConsumes = method.getAnnotation(Consumes.class);
        if (methodConsumes != null && methodConsumes.value().length > 0) {
            return methodConsumes.value();
        }
        Consumes classConsumes = source.getAnnotation(Consumes.class);
        if (classConsumes != null && classConsumes.value().length > 0) {
            return classConsumes.value();
        }
        return new String[0];
    }

    private String[] resolveProduces(Class<?> source, Method method) {
        Produces methodProduces = method.getAnnotation(Produces.class);
        if (methodProduces != null && methodProduces.value().length > 0) {
            return methodProduces.value();
        }
        Produces classProduces = source.getAnnotation(Produces.class);
        if (classProduces != null && classProduces.value().length > 0) {
            return classProduces.value();
        }
        return new String[0];
    }

    private boolean isConsumesMatched(HttpRequest request, String[] consumes) {
        if (consumes == null || consumes.length == 0) {
            return true;
        }
        String requestContentType = mediaTypeOnly(request.header("Content-Type"));
        if (requestContentType == null) {
            return false;
        }
        return Arrays.stream(consumes)
                .map(this::mediaTypeOnly)
                .filter(v -> v != null)
                .anyMatch(expected -> matchesMediaType(expected, requestContentType));
    }

    private boolean isJsonExpected(String[] consumes, String contentType) {
        if (contentType != null && matchesMediaType("application/json", contentType)) {
            return true;
        }
        if (consumes == null || consumes.length == 0) {
            return false;
        }
        for (String consume : consumes) {
            String normalized = mediaTypeOnly(consume);
            if (normalized == null) {
                continue;
            }
            if (matchesMediaType("application/json", normalized) || matchesMediaType(normalized, "application/json")) {
                return true;
            }
        }
        return false;
    }

    private String negotiateProducedType(HttpRequest request, String[] produces) {
        if (produces == null || produces.length == 0) {
            return null;
        }

        String accept = request.header("Accept");
        if (accept == null || accept.isBlank()) {
            return produces[0];
        }

        String[] accepted = accept.split(",");
        for (String acceptedType : accepted) {
            String normalizedAccepted = mediaTypeOnly(acceptedType);
            if (normalizedAccepted == null) {
                continue;
            }
            for (String producedType : produces) {
                String normalizedProduced = mediaTypeOnly(producedType);
                if (normalizedProduced == null) {
                    continue;
                }
                if (matchesMediaType(normalizedAccepted, normalizedProduced)
                        || matchesMediaType(normalizedProduced, normalizedAccepted)) {
                    return producedType;
                }
            }
        }

        return null;
    }

    private boolean matchesMediaType(String expected, String actual) {
        if ("*/*".equals(expected)) {
            return true;
        }
        if (expected.equals(actual)) {
            return true;
        }
        if (expected.endsWith("/*")) {
            String prefix = expected.substring(0, expected.indexOf('/'));
            return actual.startsWith(prefix + "/");
        }
        return false;
    }

    private String mediaTypeOnly(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    private void ensureContentType(HttpResponse response, String[] produces, String negotiated) {
        if (hasContentType(response)) {
            return;
        }
        if (negotiated != null && !negotiated.isBlank()) {
            response.header("Content-Type", negotiated);
            return;
        }
        if (produces != null && produces.length > 0 && produces[0] != null && !produces[0].isBlank()) {
            response.header("Content-Type", produces[0]);
            return;
        }
        response.header("Content-Type", "text/plain; charset=UTF-8");
    }

    private boolean hasContentType(HttpResponse response) {
        return response.getHeaders().keySet().stream().anyMatch(key -> "Content-Type".equalsIgnoreCase(key));
    }

    private String normalizePath(String path) {
        String normalized = path.replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record RouteMapping(HttpMethod method, String path, String[] consumes, String[] produces) {
    }

    private static final class BadRequestException extends RuntimeException {
        private BadRequestException(String message) {
            super(message);
        }
    }
}
