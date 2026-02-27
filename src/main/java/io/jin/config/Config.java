package io.jin.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class Config {
    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = values;
    }

    public static Config load() {
        Map<String, String> merged = new LinkedHashMap<>();
        List<String> propertiesSources = new ArrayList<>();
        List<String> yamlSources = new ArrayList<>();

        detectResource("application.properties", propertiesSources, "classpath:");
        detectResource("application.yml", yamlSources, "classpath:");
        detectResource("application.yaml", yamlSources, "classpath:");

        detectPath(Path.of("application.properties"), propertiesSources, "file:");
        detectPath(Path.of("application.yml"), yamlSources, "file:");
        detectPath(Path.of("application.yaml"), yamlSources, "file:");
        detectPath(Path.of("config", "application.properties"), propertiesSources, "file:");
        detectPath(Path.of("config", "application.yml"), yamlSources, "file:");
        detectPath(Path.of("config", "application.yaml"), yamlSources, "file:");

        boolean hasProperties = !propertiesSources.isEmpty();
        boolean hasYaml = !yamlSources.isEmpty();

        if (hasProperties) {
            loadProperties(merged, "application.properties");
            loadPropertiesFromPath(merged, Path.of("application.properties"));
            loadPropertiesFromPath(merged, Path.of("config", "application.properties"));
        } else if (hasYaml) {
            loadYaml(merged, "application.yml");
            loadYaml(merged, "application.yaml");
            loadYamlFromPath(merged, Path.of("application.yml"));
            loadYamlFromPath(merged, Path.of("application.yaml"));
            loadYamlFromPath(merged, Path.of("config", "application.yml"));
            loadYamlFromPath(merged, Path.of("config", "application.yaml"));
        }

        return new Config(Map.copyOf(merged));
    }

    public String get(String key) {
        return values.get(key);
    }

    public String getOrDefault(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    private static void loadProperties(Map<String, String> target, String resourceName) {
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                return;
            }
            loadProperties(target, in);
        } catch (IOException ignored) {
        }
    }

    private static void loadYaml(Map<String, String> target, String resourceName) {
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                return;
            }
            loadYaml(target, in);
        } catch (IOException ignored) {
        }
    }

    private static void loadPropertiesFromPath(Map<String, String> target, Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            loadProperties(target, in);
        } catch (IOException ignored) {
        }
    }

    private static void loadYamlFromPath(Map<String, String> target, Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            loadYaml(target, in);
        } catch (IOException ignored) {
        }
    }

    private static void loadProperties(Map<String, String> target, InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        for (String name : properties.stringPropertyNames()) {
            target.put(name, properties.getProperty(name));
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadYaml(Map<String, String> target, InputStream in) {
        Object loaded = new Yaml().load(in);
        if (!(loaded instanceof Map<?, ?> map)) {
            return;
        }
        flatten(target, "", (Map<String, Object>) map);
    }

    private static void flatten(Map<String, String> target, String prefix, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                flatten(target, key, nestedMap);
                continue;
            }
            if (value != null) {
                target.put(key, Objects.toString(value));
            }
        }
    }

    private static void detectResource(String name, List<String> found, String prefix) {
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream(name)) {
            if (in != null) {
                found.add(prefix + name);
            }
        } catch (IOException ignored) {
        }
    }

    private static void detectPath(Path path, List<String> found, String prefix) {
        if (Files.exists(path)) {
            found.add(prefix + path.toString());
        }
    }
}
