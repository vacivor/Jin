package io.jin.context;

import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

public final class ClassPathScanner {
    private ClassPathScanner() {
    }

    public static Set<Class<?>> scanPackage(String basePackage) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        String path = basePackage.replace('.', '/');

        try {
            var resources = Thread.currentThread().getContextClassLoader().getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if (!"file".equals(resource.getProtocol())) {
                    continue;
                }
                Path root = Path.of(URLDecoder.decode(resource.getFile(), StandardCharsets.UTF_8));
                collectClasses(classes, root, basePackage);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan package: " + basePackage, e);
        }

        return classes;
    }

    private static void collectClasses(Set<Class<?>> classes, Path root, String basePackage) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        String relative = root.relativize(path).toString();
                        String className = basePackage + "." + relative
                                .replace('/', '.')
                                .replace('\\', '.')
                                .replaceAll("\\.class$", "");
                        try {
                            classes.add(Class.forName(className));
                        } catch (ClassNotFoundException ignored) {
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk class files under: " + root, e);
        }
    }
}
