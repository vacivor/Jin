package io.jin.context;

import io.jin.annotation.Bean;
import io.jin.annotation.Configuration;
import io.jin.annotation.Value;
import io.jin.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ApplicationContext {
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> earlySingletons = new ConcurrentHashMap<>();
    private final Set<Class<?>> singletonsInCreation = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Deque<Class<?>>> creationStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final Set<Class<?>> managedTypes = ConcurrentHashMap.newKeySet();
    private final Config config;

    public ApplicationContext(Class<?>... sources) {
        this(Config.load(), sources);
    }

    public ApplicationContext(Config config, Class<?>... sources) {
        this.config = config;
        registerSources(sources);
    }

    public <T> T getBean(Class<T> type) {
        Object existing = singletons.get(type);
        if (existing != null) {
            return type.cast(existing);
        }
        Object early = earlySingletons.get(type);
        if (early != null) {
            return type.cast(early);
        }

        Class<?> candidate = resolveManagedCandidate(type);
        if (candidate == null) {
            throw new IllegalArgumentException("Type is not managed: " + type.getName());
        }
        return type.cast(createAndCache(candidate));
    }

    private void registerSources(Class<?>[] sources) {
        Arrays.stream(sources)
                .filter(Objects::nonNull)
                .filter(this::isManagedType)
                .forEach(managedTypes::add);

        for (Class<?> source : managedTypes) {
            if (source.isAnnotationPresent(Configuration.class)) {
                Object configBean = createAndCache(source);
                registerBeanMethods(configBean);
            }
        }

        for (Class<?> source : managedTypes) {
            if (!source.isAnnotationPresent(Configuration.class)) {
                createAndCache(source);
            }
        }
    }

    private boolean isManagedType(Class<?> type) {
        return type.isAnnotationPresent(Configuration.class)
                || type.isAnnotationPresent(Singleton.class)
                || type.isAnnotationPresent(Named.class)
                || type.isAnnotationPresent(Path.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T createAndCache(Class<T> type) {
        Object cached = singletons.get(type);
        if (cached != null) {
            return (T) cached;
        }

        Object early = earlySingletons.get(type);
        if (early != null) {
            return (T) early;
        }
        if (singletonsInCreation.contains(type)) {
            throw new IllegalStateException("Circular dependency detected: " + describeCycle(type));
        }

        singletonsInCreation.add(type);
        creationStack.get().push(type);
        try {
            Constructor<?> constructor = resolveConstructor(type);
            Object instance;

            if (constructor.getParameterCount() == 0) {
                constructor.setAccessible(true);
                instance = constructor.newInstance();
                earlySingletons.put(type, instance);
            } else {
                Object[] args = resolveExecutableArgs(constructor);
                constructor.setAccessible(true);
                instance = constructor.newInstance(args);
            }

            injectFields(instance);
            singletons.put(type, instance);
            earlySingletons.remove(type);
            registerByAssignableTypes(type, instance);
            return type.cast(instance);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to create bean: " + type.getName(), e);
        } finally {
            Deque<Class<?>> stack = creationStack.get();
            if (!stack.isEmpty() && stack.peek() == type) {
                stack.pop();
            } else {
                stack.remove(type);
            }
            singletonsInCreation.remove(type);
        }
    }

    private Object resolveDependency(Class<?> dependencyType) {
        Object existing = singletons.get(dependencyType);
        if (existing != null) {
            return existing;
        }
        Object early = earlySingletons.get(dependencyType);
        if (early != null) {
            return early;
        }

        Object assignable = findAssignable(singletons, dependencyType);
        if (assignable != null) {
            return assignable;
        }
        assignable = findAssignable(earlySingletons, dependencyType);
        if (assignable != null) {
            return assignable;
        }

        Class<?> candidate = resolveManagedCandidate(dependencyType);
        if (candidate != null) {
            if (singletonsInCreation.contains(candidate)) {
                Object exposed = earlySingletons.get(candidate);
                if (exposed != null) {
                    return exposed;
                }
                throw new IllegalStateException("Circular dependency detected: " + describeCycle(candidate));
            }
            return createAndCache(candidate);
        }

        throw new IllegalStateException("Unsatisfied dependency: " + dependencyType.getName());
    }

    private Class<?> resolveManagedCandidate(Class<?> dependencyType) {
        if (managedTypes.contains(dependencyType)) {
            return dependencyType;
        }
        Set<Class<?>> candidates = new LinkedHashSet<>();
        for (Class<?> managedType : managedTypes) {
            if (dependencyType.isAssignableFrom(managedType)) {
                candidates.add(managedType);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple beans found for type " + dependencyType.getName() + ": " + candidates);
        }
        return candidates.iterator().next();
    }

    private Object findAssignable(Map<Class<?>, Object> source, Class<?> dependencyType) {
        return source.entrySet().stream()
                .filter(entry -> dependencyType.isAssignableFrom(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private Constructor<?> resolveConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        Constructor<?> injectConstructor = Arrays.stream(constructors)
                .filter(c -> c.isAnnotationPresent(Inject.class))
                .findFirst()
                .orElse(null);

        if (injectConstructor != null) {
            return injectConstructor;
        }

        if (constructors.length == 1) {
            return constructors[0];
        }

        return Arrays.stream(constructors)
                .filter(c -> c.getParameterCount() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No usable constructor found for " + type.getName()));
    }

    private void registerBeanMethods(Object configurationInstance) {
        for (Method method : configurationInstance.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Bean.class)) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                throw new IllegalStateException("@Bean method must not be static: " + method.getName());
            }
            Object[] args = resolveExecutableArgs(method);
            try {
                method.setAccessible(true);
                Object bean = method.invoke(configurationInstance, args);
                if (bean == null) {
                    throw new IllegalStateException("@Bean method returned null: " + method.getName());
                }
                Class<?> beanType = method.getReturnType();
                singletons.put(beanType, bean);
                registerByAssignableTypes(beanType, bean);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to invoke @Bean method: " + method.getName(), e);
            }
        }
    }

    private Object[] resolveExecutableArgs(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            args[i] = resolveParameter(parameters[i]);
        }
        return args;
    }

    private Object resolveParameter(Parameter parameter) {
        Value value = parameter.getAnnotation(Value.class);
        if (value != null) {
            return resolveValue(value.value(), parameter.getType());
        }
        return resolveInjectTarget(parameter.getType(), parameter.getParameterizedType());
    }

    private Class<?> resolveProviderType(Type genericType) {
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type actual = parameterizedType.getActualTypeArguments()[0];
            if (actual instanceof Class<?> clazz) {
                return clazz;
            }
        }
        throw new IllegalStateException("Provider dependency must declare concrete generic type");
    }

    private void injectFields(Object bean) {
        Class<?> type = bean.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    if (field.isAnnotationPresent(Value.class)) {
                        Object value = resolveValue(field.getAnnotation(Value.class).value(), field.getType());
                        field.setAccessible(true);
                        field.set(bean, value);
                        continue;
                    }
                    if (!isInjectField(field)) {
                        continue;
                    }
                    Object value = resolveInjectTarget(field.getType(), field.getGenericType());
                    field.setAccessible(true);
                    field.set(bean, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to inject field: " + field, e);
                }
            }
            type = type.getSuperclass();
        }
    }

    private Object resolveValue(String key, Class<?> targetType) {
        String resolvedKey = key;
        String defaultValue = null;
        if (key.startsWith("${") && key.endsWith("}")) {
            String content = key.substring(2, key.length() - 1);
            int idx = content.indexOf(':');
            if (idx >= 0) {
                resolvedKey = content.substring(0, idx);
                defaultValue = content.substring(idx + 1);
            } else {
                resolvedKey = content;
            }
        }

        String raw = config.get(resolvedKey);
        if (raw == null) {
            raw = defaultValue;
        }
        if (raw == null) {
            throw new IllegalStateException("Missing config for key: " + key);
        }
        return convert(raw, targetType);
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
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<? extends Enum>) targetType, raw);
            return enumValue;
        }
        throw new IllegalStateException("Unsupported @Value target type: " + targetType.getName());
    }

    private boolean isInjectField(AnnotatedElement field) {
        return field.isAnnotationPresent(Inject.class);
    }

    private Object resolveInjectTarget(Class<?> rawType, Type genericType) {
        if (rawType == Provider.class) {
            Class<?> providedType = resolveProviderType(genericType);
            return (Provider<?>) () -> getBean(providedType);
        }
        return resolveDependency(rawType);
    }

    private String describeCycle(Class<?> targetType) {
        Deque<Class<?>> stack = creationStack.get();
        if (stack.isEmpty()) {
            return targetType.getSimpleName() + " -> " + targetType.getSimpleName();
        }

        Deque<Class<?>> reversed = new ArrayDeque<>(stack);
        Set<Class<?>> seen = new HashSet<>();
        StringBuilder path = new StringBuilder();
        Iterator<Class<?>> iterator = reversed.descendingIterator();
        while (iterator.hasNext()) {
            Class<?> type = iterator.next();
            if (!seen.add(type)) {
                continue;
            }
            if (path.length() > 0) {
                path.append(" -> ");
            }
            path.append(type.getSimpleName());
        }
        if (path.length() > 0) {
            path.append(" -> ");
        }
        path.append(targetType.getSimpleName());
        path.append(" (use Provider<T> or no-arg + @Inject field to break constructor cycle)");
        return path.toString();
    }

    private void registerByAssignableTypes(Class<?> primaryType, Object instance) {
        for (Class<?> itf : primaryType.getInterfaces()) {
            singletons.putIfAbsent(itf, instance);
        }
        Class<?> superClass = primaryType.getSuperclass();
        while (superClass != null && superClass != Object.class) {
            singletons.putIfAbsent(superClass, instance);
            superClass = superClass.getSuperclass();
        }
    }
}
