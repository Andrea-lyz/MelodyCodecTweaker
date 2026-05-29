package xyz.melodylsp.codec.host;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflection-only handle to a {@code androidx.preference.Preference} (or any subclass) instance
 * living inside the host classpath. The host App ships androidx.preference but R8-minified
 * (e.g. {@code PreferenceFragmentCompat} → {@code androidx.preference.g}). Importing the
 * compile-time symbol from the module APK causes a {@link NoClassDefFoundError} at runtime
 * because the literal class name is gone — so we keep everything dynamic.
 *
 * <p>Each helper method below resolves the target {@link Method} once and caches it; failures
 * are swallowed and logged so a host APK that strips a particular setter never crashes the
 * panel.</p>
 */
public final class PrefRef {

    /** Loads {@code className} from the host classloader. Returns {@code null} on failure. */
    public static Class<?> load(ClassLoader cl, String className) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** New instance via {@code (Context)} constructor. Returns {@code null} on failure. */
    public static Object newInstance(Class<?> cls, Context context) {
        try {
            Constructor<?> ctor = cls.getConstructor(Context.class);
            return ctor.newInstance(context);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Prefer {@code (Context, AttributeSet)} which lets COUI / androidx.preference resolve the
     * default style attribute from the host theme (e.g.
     * {@code R.attr.preferenceCategoryStyle}). Without this, programmatically-created
     * {@link com.coui.appcompat.preference.COUIPreferenceCategory} instances paint their title
     * with the framework default white-on-light color (visible on white background as if blank)
     * and ignore dark-mode tinting. Falls back to the single-arg constructor.
     */
    public static Object newInstanceWithAttrs(Class<?> cls, Context context) {
        try {
            Constructor<?> ctor = cls.getConstructor(
                    Context.class, android.util.AttributeSet.class);
            return ctor.newInstance(context, null);
        } catch (Throwable ignored) {
        }
        return newInstance(cls, context);
    }

    /**
     * Set the {@code mKey} field. {@code androidx.preference.Preference#setKey} accepts a
     * {@code String} (not {@code CharSequence}), so we must reflect against {@code String.class}
     * — using {@code CharSequence.class} silently no-ops, leaving every Preference with
     * {@code mKey == null} and triggering an {@code "Key cannot be null"} crash the moment any
     * {@code PreferenceDialogFragmentCompat} tries to look itself back up by key.
     */
    public static void setKey(Object pref, String key) {
        invokeSetter(pref, "setKey", String.class, key);
    }

    public static void setTitle(Object pref, CharSequence title) {
        invokeSetter(pref, "setTitle", CharSequence.class, title);
    }

    public static CharSequence getTitle(Object pref) {
        Object r = invoke(pref, "getTitle", new Class[0], new Object[0]);
        return r instanceof CharSequence ? (CharSequence) r : null;
    }

    public static void setSummary(Object pref, CharSequence summary) {
        invokeSetter(pref, "setSummary", CharSequence.class, summary);
    }

    public static CharSequence getSummary(Object pref) {
        Object r = invoke(pref, "getSummary", new Class[0], new Object[0]);
        return r instanceof CharSequence ? (CharSequence) r : null;
    }

    public static void setOrder(Object pref, int order) {
        invokeSetter(pref, "setOrder", int.class, order);
    }

    public static void setVisible(Object pref, boolean visible) {
        invokeSetter(pref, "setVisible", boolean.class, visible);
    }

    public static boolean isVisible(Object pref) {
        Object r = invoke(pref, "isVisible", new Class[0], new Object[0]);
        return !(r instanceof Boolean) || (Boolean) r;
    }

    public static void setSelectable(Object pref, boolean selectable) {
        invokeSetter(pref, "setSelectable", boolean.class, selectable);
    }

    public static void setIconSpaceReserved(Object pref, boolean reserved) {
        invokeSetter(pref, "setIconSpaceReserved", boolean.class, reserved);
    }

    public static void setPersistent(Object pref, boolean persistent) {
        invokeSetter(pref, "setPersistent", boolean.class, persistent);
    }

    public static void setEnabled(Object pref, boolean enabled) {
        invokeSetter(pref, "setEnabled", boolean.class, enabled);
    }

    public static void setDisabled(Object pref, boolean disabled) {
        if (!invokeVoidStrict(pref, "setDisabled",
                new Class[]{boolean.class}, new Object[]{disabled})) {
            setEnabled(pref, !disabled);
        }
    }

    public static void setChecked(Object pref, boolean checked) {
        invokeSetter(pref, "setChecked", boolean.class, checked);
    }

    public static int getLayoutResource(Object pref) {
        Object r = invoke(pref, "getLayoutResource", new Class[0], new Object[0]);
        return r instanceof Integer ? (Integer) r : 0;
    }

    public static void setLayoutResource(Object pref, int resId) {
        invokeSetter(pref, "setLayoutResource", int.class, resId);
    }

    public static int getWidgetLayoutResource(Object pref) {
        Object r = invoke(pref, "getWidgetLayoutResource", new Class[0], new Object[0]);
        return r instanceof Integer ? (Integer) r : 0;
    }

    public static void setWidgetLayoutResource(Object pref, int resId) {
        invokeSetter(pref, "setWidgetLayoutResource", int.class, resId);
    }

    public static int getOrder(Object pref) {
        Object r = invoke(pref, "getOrder", new Class[0], new Object[0]);
        return r instanceof Integer ? (Integer) r : -1;
    }

    public static String getKey(Object pref) {
        Object r = invoke(pref, "getKey", new Class[0], new Object[0]);
        return r != null ? r.toString() : null;
    }

    /**
     * Returns the {@code PreferenceGroup} parent of a {@link androidx.preference.Preference}.
     * AOSP exposes this as {@code Preference#getParent()}; under R8 that name is preserved on
     * the public api surface so we just call it. Returns {@code null} if the Preference is
     * detached or the call fails.
     */
    public static Object getParent(Object pref) {
        return invoke(pref, "getParent", new Class[0], new Object[0]);
    }

    /**
     * Calls {@code addPreference(Preference)} on the given screen / category. The method name
     * itself has been R8-renamed inside the host APK; we resolve it by signature instead —
     * pick any 1-arg method whose parameter type extends {@code androidx.preference.Preference}
     * and which returns a {@code boolean} (the actual signature is
     * {@code boolean addPreference(Preference)}, but R8 may also have stripped the boolean to
     * void, so we accept void too).
     */
    public static void addPreference(Object container, Object pref) {
        if (container == null || pref == null) return;
        ClassLoader cl = container.getClass().getClassLoader();
        Class<?> prefBase = load(cl, "androidx.preference.Preference");
        if (prefBase == null) {
            xyz.melodylsp.codec.util.MLog.w("addPreference: androidx.preference.Preference class missing");
            return;
        }
        Method method = findUnaryMethod(container.getClass(), prefBase, /* allowVoidReturn */ true);
        if (method == null) {
            xyz.melodylsp.codec.util.MLog.w("addPreference: no addPreference-shaped method on "
                    + container.getClass().getName());
            return;
        }
        try {
            method.setAccessible(true);
            method.invoke(container, pref);
        } catch (Throwable t) {
            xyz.melodylsp.codec.util.MLog.w("addPreference reflective invoke failed", t);
        }
    }

    /**
     * Find a 1-arg method (any name) accepting {@code paramBase} (or a subclass) on the given
     * class hierarchy. Used to resolve R8-renamed library setters. Returns the first match;
     * picks the most-derived parameter type when ties exist by walking subclasses-first.
     */
    private static Method findUnaryMethod(Class<?> startCls, Class<?> paramBase, boolean allowVoidReturn) {
        Class<?> cls = startCls;
        Method best = null;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!paramBase.isAssignableFrom(p)) continue;
                Class<?> ret = m.getReturnType();
                if (!allowVoidReturn && ret == void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                if (best == null) {
                    best = m;
                } else {
                    // Prefer the method whose parameter type is closer to paramBase (i.e. the
                    // exact match) so that we avoid picking an unrelated setter taking the same
                    // base type.
                    if (best.getParameterTypes()[0] != paramBase && p == paramBase) {
                        best = m;
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return best;
    }

    /**
     * Calls {@code findPreference(CharSequence)} on a screen / fragment / category.
     *
     * <p>The host APK is R8-minified so the method has been renamed (e.g. {@code findPreference}
     * → {@code d}). We probe a small set of plausible names; the first one that returns
     * non-null wins. This is intentionally tolerant — a future host version may map the name
     * differently.</p>
     */
    public static Object findPreference(Object container, CharSequence key) {
        if (container == null) return null;
        Object r = invoke(container, "findPreference",
                new Class[]{CharSequence.class}, new Object[]{key});
        if (r != null) return r;
        // Fallback: scan for any single-arg method taking CharSequence, returning Object.
        Class<?> cls = container.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params[0] != CharSequence.class && params[0] != String.class) continue;
                if (m.getReturnType() == void.class) continue;
                if (m.getReturnType().isPrimitive()) continue;
                try {
                    m.setAccessible(true);
                    Object out = m.invoke(container,
                            params[0] == String.class ? key.toString() : key);
                    if (out != null && looksLikePreference(out)) return out;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Heuristic check: an object is "Preference-like" if it has both a {@code setKey} and a
     * {@code getKey} accessor (any parameter / return type).
     */
    private static boolean looksLikePreference(Object obj) {
        boolean hasSet = false, hasGet = false;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!hasGet && m.getName().equals("getKey") && m.getParameterCount() == 0) hasGet = true;
                if (!hasSet && m.getName().equals("setKey") && m.getParameterCount() == 1) hasSet = true;
                if (hasGet && hasSet) return true;
            }
            cls = cls.getSuperclass();
        }
        return hasGet || hasSet;
    }

    /** Calls {@code getPreferenceScreen()} on a fragment, with PreferenceManager fallback. */
    public static Object getPreferenceScreen(Object fragment) {
        Object screen = invoke(fragment, "getPreferenceScreen", new Class[0], new Object[0]);
        if (screen != null) return screen;
        Object pm = invoke(fragment, "getPreferenceManager", new Class[0], new Object[0]);
        if (pm != null) {
            Object via = invoke(pm, "getPreferenceScreen", new Class[0], new Object[0]);
            if (via != null) return via;
        }
        // Last resort: walk fields recursively up to depth 2 and pick anything whose type is
        // androidx.preference.PreferenceScreen. The class name itself is preserved by R8 even
        // when method / field names are minified.
        Object found = findPreferenceScreenInFields(fragment, 2);
        if (found == null) {
            xyz.melodylsp.codec.util.MLog.w(
                    "getPreferenceScreen: no PreferenceScreen found via methods or field scan; "
                            + "fragment class=" + fragment.getClass().getName());
        }
        return found;
    }

    private static Object findPreferenceScreenInFields(Object root, int maxDepth) {
        if (root == null || maxDepth < 0) return null;
        Class<?> cls = root.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                String name = ft.getName();
                if ("androidx.preference.PreferenceScreen".equals(name)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v != null) return v;
                    } catch (Throwable ignored) {
                    }
                }
                if (name.endsWith("$PreferenceScreen") || name.endsWith(".PreferenceScreen")) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v != null) return v;
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        // Recurse: walk into any non-primitive field once and probe the same way.
        if (maxDepth == 0) return null;
        cls = root.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class || ft.isArray()) continue;
                if (ft.getName().startsWith("java.")) continue;
                if (ft.getName().startsWith("android.")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(root);
                    if (v == null) continue;
                    Object found = findPreferenceScreenInFields(v, maxDepth - 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Calls {@code getPreferenceCount()} on a screen / category. */
    public static int getPreferenceCount(Object container) {
        Object r = invoke(container, "getPreferenceCount", new Class[0], new Object[0]);
        if (r instanceof Integer) return (Integer) r;
        // Fallback: PreferenceGroup keeps its children in a List field. Read that list's size.
        java.util.List<?> children = getChildrenList(container);
        return children != null ? children.size() : 0;
    }

    /**
     * Calls {@code getPreference(int)} on a screen / category. Falls back to a children-list
     * field scan if R8 stripped the public method name. Returns {@code null} if both paths
     * fail.
     */
    public static Object getPreference(Object container, int index) {
        Object r = invoke(container, "getPreference",
                new Class[]{int.class}, new Object[]{index});
        if (r != null) return r;
        java.util.List<?> list = getChildrenList(container);
        if (list != null && index >= 0 && index < list.size()) {
            return list.get(index);
        }
        return null;
    }

    /**
     * Returns the children {@code List<Preference>} that {@code PreferenceGroup} maintains
     * (decompiled name {@code f10496c}). Identified by being the only {@code List} field on the
     * group whose contents look Preference-shaped (have a {@code getKey} accessor).
     */
    private static java.util.List<?> getChildrenList(Object container) {
        if (container == null) return null;
        Class<?> cls = container.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(container);
                    if (!(v instanceof java.util.List)) continue;
                    java.util.List<?> list = (java.util.List<?>) v;
                    if (list.isEmpty()) {
                        // Empty list could still be the children list (no items added yet).
                        // Remember and keep looking; a non-empty Preference list wins.
                        // For now skip empty so we don't false-positive on an unrelated list.
                        continue;
                    }
                    Object first = list.get(0);
                    if (looksLikePreference(first)) return list;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Returns true on success. {@link #invokeVoid} swallows failures, this one reports. */
    private static boolean invokeVoidStrict(Object target, String name,
            Class<?>[] paramTypes, Object[] args) {
        if (target == null) return false;
        Method m = findMethod(target.getClass(), name, paramTypes);
        if (m == null) return false;
        try {
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name, paramTypes);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            xyz.melodylsp.codec.util.MLog.w("PrefRef." + name + " failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Invoke a 1-arg {@code void} setter by name, falling back to a signature-based lookup when
     * the literal name has been stripped / renamed by R8 (TODO A2).
     *
     * <p>Historically every setter resolved purely by name; an R8 rename therefore failed
     * <em>silently</em> (no exception — the value just never landed, leaving the UI blank, as
     * with the {@code setKey} regression we already hit). The fallback below re-discovers the
     * setter by its parameter type when, and only when, the resolution is unambiguous:
     * exactly one declared 1-arg {@code void} method on the whole hierarchy accepts
     * {@code paramType}. If two or more candidates exist (e.g. several {@code boolean} setters
     * that R8 renamed to single letters) we deliberately do <strong>not</strong> guess —
     * writing the value into the wrong setter would be worse than the no-op. {@code String} /
     * {@code CharSequence} are treated as interchangeable for matching because
     * {@code androidx.preference} mixes the two across {@code setKey} / {@code setTitle}.</p>
     */
    private static void invokeSetter(Object target, String name, Class<?> paramType, Object value) {
        if (target == null) return;
        Method byName = findMethod(target.getClass(), name, new Class[]{paramType});
        if (byName != null) {
            try {
                byName.setAccessible(true);
                byName.invoke(target, value);
                return;
            } catch (Throwable t) {
                xyz.melodylsp.codec.util.MLog.w("PrefRef." + name + " failed: " + t.getMessage());
                return;
            }
        }
        Method bySig = findUniqueVoidSetter(target.getClass(), paramType);
        if (bySig == null) {
            xyz.melodylsp.codec.util.MLog.w("PrefRef." + name
                    + ": no name match and no unique " + paramType.getName()
                    + " setter on " + target.getClass().getName());
            return;
        }
        try {
            bySig.setAccessible(true);
            bySig.invoke(target, value);
            xyz.melodylsp.codec.util.MLog.event("prefref.setter.sigfallback",
                    "logical", name, "resolved", bySig.getName(),
                    "type", paramType.getSimpleName());
        } catch (Throwable t) {
            xyz.melodylsp.codec.util.MLog.w("PrefRef." + name + " sig-fallback failed: "
                    + t.getMessage());
        }
    }

    /**
     * Find the single 1-arg {@code void} method (any name) on the class hierarchy whose
     * parameter accepts {@code paramType}. Returns {@code null} when there is no match or the
     * match is ambiguous (more than one candidate), so callers never write into a wrong setter.
     * {@code String} and {@code CharSequence} are unified so a {@code CharSequence}-typed
     * setter still matches a {@code String} request and vice-versa.
     */
    private static Method findUniqueVoidSetter(Class<?> startCls, Class<?> paramType) {
        boolean textType = paramType == String.class || paramType == CharSequence.class;
        Method match = null;
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                Class<?> p = m.getParameterTypes()[0];
                boolean accepts;
                if (textType) {
                    accepts = p == String.class || p == CharSequence.class;
                } else {
                    accepts = p == paramType;
                }
                if (!accepts) continue;
                if (match != null && !match.getName().equals(m.getName())) {
                    // Ambiguous: two differently-named setters take the same type. Bail.
                    return null;
                }
                match = m;
            }
            cls = cls.getSuperclass();
        }
        return match;
    }

    private static void invokeVoid(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        invoke(target, name, paramTypes, args);
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>[] paramTypes) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private PrefRef() {
    }
}
