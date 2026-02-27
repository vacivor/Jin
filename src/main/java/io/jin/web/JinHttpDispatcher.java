package io.jin.web;

public class JinHttpDispatcher implements HttpHandler {
    private final RouteRegistry routeRegistry;

    public JinHttpDispatcher(RouteRegistry routeRegistry) {
        this.routeRegistry = routeRegistry;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        RouteRegistry.RouteMatch routeMatch = routeRegistry.match(request.method(), request.path());
        if (routeMatch == null) {
            return HttpResponse.of(404, "Not Found: " + request.path());
        }
        HttpRequest withPathParams = request.withPathParams(routeMatch.pathParams());
        return routeMatch.handler().handle(withPathParams);
    }
}
