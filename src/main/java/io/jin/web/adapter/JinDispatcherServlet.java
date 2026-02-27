package io.jin.web.adapter;

import io.jin.web.HttpHandler;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public abstract class JinDispatcherServlet extends HttpServlet {
    private final HttpHandler handler;

    protected JinDispatcherServlet(HttpHandler handler) {
        this.handler = handler;
    }

    protected HttpHandler handler() {
        return handler;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doService(req, resp);
    }

    protected abstract void doService(HttpServletRequest req, HttpServletResponse resp) throws IOException;
}
