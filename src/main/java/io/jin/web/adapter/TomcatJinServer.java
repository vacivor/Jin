package io.jin.web.adapter;

import io.jin.web.HttpHandler;
import io.jin.web.JinServer;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class TomcatJinServer implements JinServer {
    private final int port;
    private final HttpHandler handler;
    private Tomcat tomcat;

    public TomcatJinServer(int port, HttpHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    @Override
    public void start() {
        this.tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector();

        try {
            File baseDir = Files.createTempDirectory("jin-tomcat").toFile();
            tomcat.setBaseDir(baseDir.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Tomcat base dir", e);
        }

        JinServlet.setGlobalHandler(handler);
        Context context = tomcat.addContext("", new File(".").getAbsolutePath());
        Tomcat.addServlet(context, "jinServlet", new JinServlet());
        context.addServletMappingDecoded("/*", "jinServlet");

        try {
            tomcat.start();
            System.out.println("Jin running on Tomcat at http://localhost:" + port);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Tomcat server", e);
        }
    }

    @Override
    public void stop() {
        if (tomcat == null) {
            return;
        }
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stop Tomcat server", e);
        }
    }
}
