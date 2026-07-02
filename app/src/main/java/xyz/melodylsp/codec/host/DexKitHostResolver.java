package xyz.melodylsp.codec.host;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import xyz.melodylsp.codec.util.MLog;

/**
 * Small reflective wrapper around DexKit.
 *
 * <p>DexKit is intentionally isolated from the rest of the hook code: it discovers host
 * classes by stable string literals, while existing reflection/hook paths continue to do the
 * actual work. If DexKit fails to load on a ROM/framework combination, callers simply keep
 * using their hard-coded and Activity-scan fallbacks.</p>
 */
final class DexKitHostResolver implements AutoCloseable {

    private static volatile boolean libraryLoaded;

    private final String apkPath;
    private Object bridge;
    private boolean unavailable;

    DexKitHostResolver(String apkPath) {
        this.apkPath = apkPath;
    }

    List<String> findClassesUsingStrings(
            String label,
            String searchPackage,
            String... strings) {
        if (searchPackage == null || searchPackage.isEmpty()
                || strings == null || strings.length == 0) {
            return java.util.Collections.emptyList();
        }
        if (!ensureBridge()) return java.util.Collections.emptyList();
        try {
            Object findClass = create("org.luckypray.dexkit.query.FindClass");
            Object matcher = create("org.luckypray.dexkit.query.matchers.ClassMatcher");
            findClass = keepIfReturned(
                    invokeVararg(findClass, "searchPackages", String[].class,
                            new String[]{searchPackage}),
                    findClass);
            matcher = keepIfReturned(invokeVararg(matcher, "usingStrings", String[].class, strings),
                    matcher);
            findClass = keepIfReturned(invoke(findClass, "matcher", matcher), findClass);

            Object result = invoke(bridge, "findClass", findClass);
            List<String> names = classNames(result);
            MLog.event(names.isEmpty() ? "dexkit.find.empty" : "dexkit.find.classes",
                    "label", label,
                    "package", searchPackage,
                    "count", names.size(),
                    "classes", join(names, 6));
            return names;
        } catch (Throwable t) {
            MLog.w("DexKit class lookup failed label=" + label, t);
            return java.util.Collections.emptyList();
        }
    }

    private boolean ensureBridge() {
        if (bridge != null) return true;
        if (unavailable) return false;
        if (apkPath == null || apkPath.isEmpty()) {
            unavailable = true;
            MLog.event("dexkit.unavailable", "reason", "empty_apk_path");
            return false;
        }
        try {
            loadLibraryOnce();
            Class<?> bridgeClass = Class.forName("org.luckypray.dexkit.DexKitBridge");
            bridge = bridgeClass.getMethod("create", String.class).invoke(null, apkPath);
            MLog.event("dexkit.bridge.created");
            return true;
        } catch (Throwable t) {
            unavailable = true;
            MLog.w("DexKit bridge unavailable", t);
            return false;
        }
    }

    private static void loadLibraryOnce() {
        if (libraryLoaded) return;
        synchronized (DexKitHostResolver.class) {
            if (libraryLoaded) return;
            System.loadLibrary("dexkit");
            libraryLoaded = true;
        }
    }

    private static Object create(String className) throws Exception {
        Class<?> cls = Class.forName(className);
        return cls.getMethod("create").invoke(null);
    }

    private static Object keepIfReturned(Object returned, Object fallback) {
        return returned != null ? returned : fallback;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Method method = findMethod(target.getClass(), name, args);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object invokeVararg(
            Object target,
            String name,
            Class<?> arrayType,
            Object arrayArg) throws Exception {
        Method method = findMethod(target.getClass(), name, arrayType);
        method.setAccessible(true);
        return method.invoke(target, arrayArg);
    }

    private static Method findMethod(Class<?> cls, String name, Object[] args)
            throws NoSuchMethodException {
        Method[] methods = cls.getMethods();
        for (Method method : methods) {
            if (!name.equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                Object arg = args[i];
                if (arg != null && !box(params[i]).isInstance(arg)) {
                    ok = false;
                    break;
                }
            }
            if (ok) return method;
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method[] methods = cls.getMethods();
        for (Method method : methods) {
            if (!name.equals(method.getName())) continue;
            Class<?>[] actual = method.getParameterTypes();
            if (actual.length != params.length) continue;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!actual[i].isAssignableFrom(params[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return method;
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    private static Class<?> box(Class<?> cls) {
        if (!cls.isPrimitive()) return cls;
        if (cls == boolean.class) return Boolean.class;
        if (cls == byte.class) return Byte.class;
        if (cls == char.class) return Character.class;
        if (cls == short.class) return Short.class;
        if (cls == int.class) return Integer.class;
        if (cls == long.class) return Long.class;
        if (cls == float.class) return Float.class;
        if (cls == double.class) return Double.class;
        if (cls == void.class) return Void.class;
        return cls;
    }

    private static List<String> classNames(Object result) {
        Set<String> names = new LinkedHashSet<>();
        appendClassNames(result, names);
        return new ArrayList<>(names);
    }

    private static void appendClassNames(Object value, Set<String> out) {
        if (value == null) return;
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                appendClassNames(item, out);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int count = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < count; i++) {
                appendClassNames(java.lang.reflect.Array.get(value, i), out);
            }
            return;
        }
        Object list = tryNoArg(value, "toList");
        if (list != null && list != value) {
            appendClassNames(list, out);
            return;
        }
        Object all = tryNoArg(value, "getAll");
        if (all != null && all != value) {
            appendClassNames(all, out);
            return;
        }
        String name = stringNoArg(value, "getName");
        if (name == null) name = stringNoArg(value, "getClassName");
        if (name == null) name = stringNoArg(value, "getTypeName");
        if (name != null && !name.isEmpty()) {
            out.add(name);
        }
    }

    private static Object tryNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringNoArg(Object target, String name) {
        Object value = tryNoArg(target, name);
        return value instanceof String ? (String) value : null;
    }

    private static String join(List<String> values, int max) {
        if (values == null || values.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        int count = Math.min(values.size(), Math.max(1, max));
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        if (values.size() > count) {
            sb.append(",...");
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (bridge == null) return;
        try {
            if (bridge instanceof AutoCloseable) {
                ((AutoCloseable) bridge).close();
            } else {
                Method close = bridge.getClass().getMethod("close");
                close.invoke(bridge);
            }
            MLog.event("dexkit.bridge.closed");
        } catch (Throwable t) {
            MLog.w("DexKit bridge close failed", t);
        } finally {
            bridge = null;
        }
    }
}
