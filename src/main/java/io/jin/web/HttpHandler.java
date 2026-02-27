package io.jin.web;

@FunctionalInterface
public interface HttpHandler {
    HttpResponse handle(HttpRequest request);
}
