package io.jin.web;

import io.jin.context.ApplicationContext;
import io.jin.context.ClassPathScanner;
import io.jin.config.Config;

import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class JinApplication {
    private JinApplication() {
    }

    public static JinServer run(ServerType type, int port, Class<?>... sources) {
        ApplicationContext context = new ApplicationContext(sources);
        return start(type, port, context, sources);
    }

    public static JinServer run(Class<?> bootstrapClass) {
        Config config = Config.load();
        ServerType type = resolveServerType(config);
        int port = parsePort(config.getOrDefault("server.port", "8080"));
        return run(type, port, bootstrapClass);
    }

    public static JinServer run(ServerType type, Class<?> bootstrapClass) {
        Config config = Config.load();
        int port = parsePort(config.getOrDefault("server.port", "8080"));
        return run(type, port, bootstrapClass);
    }

    public static JinServer run(ServerType type, int port, Class<?> bootstrapClass) {
        String basePackage = bootstrapClass.getPackageName();
        Set<Class<?>> scanned = new LinkedHashSet<>(ClassPathScanner.scanPackage(basePackage));
        scanned.add(bootstrapClass);
        Class<?>[] sources = scanned.toArray(new Class<?>[0]);
        ApplicationContext context = new ApplicationContext(sources);
        return start(type, port, context, sources);
    }

    private static JinServer start(ServerType type, int port, ApplicationContext context, Class<?>[] sources) {
        RouteRegistry routeRegistry = new RouteRegistry();
        ControllerRegistrar controllerRegistrar = new ControllerRegistrar(context, routeRegistry);
        controllerRegistrar.registerControllers(sources);

        JinHttpDispatcher dispatcher = new JinHttpDispatcher(routeRegistry);
        JinServer server = instantiateServer(type, port, dispatcher);
        server.start();
        return server;
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid server.port value: " + raw, e);
        }
    }

    private static ServerType resolveServerType(Config config) {
        String raw = config.getOrDefault("server.type", "netty");
        try {
            return ServerType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid server.type value: " + raw + " (supported: netty, undertow, tomcat)", e);
        }
    }

    private static JinServer instantiateServer(ServerType type, int port, HttpHandler handler) {
        String className = switch (type) {
            case NETTY -> "io.jin.web.adapter.NettyJinServer";
            case TOMCAT -> "io.jin.web.adapter.TomcatJinServer";
            case UNDERTOW -> "io.jin.web.adapter.UndertowJinServer";
        };

        try {
            Class<?> serverClass = Class.forName(className);
            Constructor<?> constructor = serverClass.getConstructor(int.class, HttpHandler.class);
            return (JinServer) constructor.newInstance(port, handler);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Server adapter not found for " + type
                    + ". Enable runtime dependency with Gradle property: -Pjin.runtime="
                    + type.name().toLowerCase(Locale.ROOT), e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to initialize server adapter: " + className, e);
        }
    }
}
