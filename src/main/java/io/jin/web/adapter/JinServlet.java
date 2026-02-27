package io.jin.web.adapter;

import io.jin.web.HttpHandler;
import io.jin.web.HttpMethod;
import io.jin.web.HttpRequest;
import io.jin.web.HttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class JinServlet extends JinDispatcherServlet {
    private static volatile HttpHandler globalHandler;

    public JinServlet() {
        super(request -> {
            if (globalHandler == null) {
                return HttpResponse.of(503, "Jin handler is not initialized");
            }
            return globalHandler.handle(request);
        });
    }

    public static void setGlobalHandler(HttpHandler handler) {
        globalHandler = handler;
    }

    @Override
    protected void doService(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpMethod method = HttpMethod.fromToken(req.getMethod());
        if (method == null) {
            resp.setStatus(405);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().write("Method Not Allowed: " + req.getMethod());
            return;
        }
        HttpRequest request = new HttpRequest(
                method,
                req.getRequestURI(),
                req.getQueryString(),
                extractHeaders(req),
                req.getReader().lines().reduce("", String::concat)
        );
        HttpResponse response = handler().handle(request);

        resp.setStatus(response.getStatus());
        response.getHeaders().forEach(resp::setHeader);
        resp.getWriter().write(response.getBody());
    }

    private Map<String, String> extractHeaders(HttpServletRequest req) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, req.getHeader(name));
        }
        return headers;
    }
}
