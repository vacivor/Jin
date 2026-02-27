package io.jin.web;

import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private int status = 200;
    private String body = "";
    private final Map<String, String> headers = new HashMap<>();

    public static HttpResponse ok(String body) {
        HttpResponse response = new HttpResponse();
        response.setBody(body);
        return response;
    }

    public static HttpResponse of(int status, String body) {
        HttpResponse response = new HttpResponse();
        response.setStatus(status);
        response.setBody(body);
        return response;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public HttpResponse header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }
}
