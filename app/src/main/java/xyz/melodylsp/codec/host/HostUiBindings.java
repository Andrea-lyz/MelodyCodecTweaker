package xyz.melodylsp.codec.host;

import android.content.Context;
import android.content.DialogInterface;
import android.app.Dialog;
import android.widget.BaseAdapter;
import android.widget.PopupWindow;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import xyz.melodylsp.codec.util.MLog;

/** Immutable, per-host-process bindings. Rebuilt from the current APK on every process start. */
final class HostUiBindings {
    static final HostUiBindings EMPTY = new HostUiBindings(null, null);

    final String dialogBuilder;
    final String[] popup;

    HostUiBindings(String dialogBuilder, String[] popup) {
        this.dialogBuilder = dialogBuilder;
        this.popup = popup;
    }

    static HostUiBindings resolve(DexKitHostResolver dexKit, ClassLoader loader) {
        // These library-owned log tags survive R8 package/class renaming. Search the entire
        // APK, including secondary DEX files, then require an unambiguous UI contract.
        String dialog = selectDialogBuilder(dexKit.findClassesUsingStrings(
                "ui.dialog", "", "COUIAlertDialogBuilder"), loader);
        List<String[]> popups = new ArrayList<>();
        for (String name : dexKit.findClassesUsingStrings(
                "ui.popup", "", "COUIPopupListWindow")) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                if (!PopupWindow.class.isAssignableFrom(cls)
                        || CodecController.findCouiSetItemsMethod(cls) == null
                        || CodecController.findCouiPopupShowMethod(cls) == null
                        || CodecController.findCouiListenerSetter(cls) == null) continue;
                cls.getConstructor(Context.class);
                String[] binding = resolvePopupItem(cls, dexKit, loader);
                if (binding != null) popups.add(binding);
            } catch (Throwable t) {
                MLog.d("evt=ui.bindings.candidate_failed class=" + name
                        + " error=" + t.getClass().getSimpleName());
            }
        }
        String[] popup = popups.size() == 1 ? popups.get(0) : null;
        MLog.event("ui.bindings.resolved", "source", "dexkit",
                "dialog", dialog != null ? dialog : "unresolved",
                "popup", popup != null ? popup[0] : "unresolved",
                "item", popup != null ? popup[1] : "unresolved",
                "popupCandidates", popups.size());
        return new HostUiBindings(dialog, popup);
    }

    static String selectDialogBuilder(List<String> names, ClassLoader loader) {
        Set<String> valid = new LinkedHashSet<>();
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                cls.getConstructor(Context.class);
                Method show = cls.getMethod("show");
                if (Modifier.isAbstract(cls.getModifiers())
                        || !Dialog.class.isAssignableFrom(show.getReturnType())) continue;
                cls.getMethod("setTitle", CharSequence.class);
                cls.getMethod("setMessage", CharSequence.class);
                cls.getMethod("setPositiveButton", CharSequence.class,
                        DialogInterface.OnClickListener.class);
                cls.getMethod("setNegativeButton", CharSequence.class,
                        DialogInterface.OnClickListener.class);
                valid.add(name);
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
        }
        return valid.size() == 1 ? valid.iterator().next() : null;
    }

    private static String[] resolvePopupItem(
            Class<?> popup, DexKitHostResolver dexKit, ClassLoader loader) {
        Set<Class<?>> items = new LinkedHashSet<>();
        for (Method method : popup.getMethods()) {
            if (method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class
                    && CodecController.popupItemFields(method.getReturnType()) != null) {
                items.add(method.getReturnType());
            }
        }
        if (items.size() != 1) return null;
        Class<?> item = items.iterator().next();
        try {
            item.getConstructor();
            Set<Field> enabledCandidates = new LinkedHashSet<>();
            for (Field adapter : popup.getDeclaredFields()) {
                if (!BaseAdapter.class.isAssignableFrom(adapter.getType())) continue;
                Method isEnabled = adapter.getType().getMethod("isEnabled", int.class);
                Field enabled = dexKit.findBooleanReadBy(isEnabled, item, loader);
                if (enabled != null) enabledCandidates.add(enabled);
            }
            // New COUI adapters use isEnabled(int) only to skip divider rows. In that case
            // the item builder's reset -> build contract proves the field roles instead:
            // a fresh ordinary item is enabled and not selected. No Views are inflated.
            Field enabled = enabledCandidates.size() == 1
                    ? enabledCandidates.iterator().next() : null;
            if (enabledCandidates.isEmpty()) enabled = defaultEnabledField(item);
            if (enabled == null) return null;
            Field[] fields = itemFields(item, enabled);
            if (fields == null) return null;
            return new String[]{popup.getName(), item.getName(), fields[0].getName(),
                    fields[1].getName(), fields[2].getName(), ""};
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    static Field defaultEnabledField(Class<?> item) {
        Field[] fields = CodecController.popupItemFields(item);
        if (fields == null) return null;
        try {
            Field enabled = enabledInDefaultItem(item.getConstructor().newInstance(), fields);
            if (enabled != null) return enabled;
            Set<Field> candidates = new LinkedHashSet<>();
            for (Class<?> builder : item.getDeclaredClasses()) {
                if (builder.getSuperclass() != Object.class
                        || !Modifier.isStatic(builder.getModifiers())
                        || !isPlainItemBuilder(builder)) continue;
                Method factory = null;
                Method reset = null;
                boolean ambiguous = false;
                for (Method method : builder.getDeclaredMethods()) {
                    if (!Modifier.isPublic(method.getModifiers())
                            || Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 0) continue;
                    if (method.getReturnType() == item) {
                        if (factory != null) ambiguous = true;
                        factory = method;
                    } else if (method.getReturnType() == void.class) {
                        if (reset != null) ambiguous = true;
                        reset = method;
                    }
                }
                if (ambiguous || factory == null || reset == null) continue;
                Object value = newItemBuilder(builder);
                reset.invoke(value);
                enabled = enabledInDefaultItem(factory.invoke(value), fields);
                if (enabled != null) candidates.add(enabled);
            }
            return candidates.size() == 1 ? candidates.iterator().next() : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isPlainItemBuilder(Class<?> builder) {
        for (Field field : builder.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (!type.isPrimitive() && type != String.class
                    && type != android.graphics.drawable.Drawable.class
                    && !ArrayList.class.isAssignableFrom(type)) return false;
        }
        return true;
    }

    private static Object newItemBuilder(Class<?> builder) throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = builder.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException strippedByR8) {
            // COUI 17.6.6 keeps the field-only builder's reset/build methods but strips its
            // unused constructor. Allocate only this shape-checked value holder, then reset
            // it before reading defaults. No View, Activity or host singleton is allocated.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Object unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, builder);
        }
    }

    private static Field enabledInDefaultItem(Object value, Field[] fields)
            throws IllegalAccessException {
        boolean first = fields[1].getBoolean(value);
        boolean second = fields[2].getBoolean(value);
        if (first == second) return null;
        return first ? fields[1] : fields[2];
    }

    /** Resolve selected as the other boolean only after the adapter proved enabled's role. */
    static Field[] itemFields(Class<?> item, Field enabled) {
        Field[] fields = CodecController.popupItemFields(item);
        if (fields == null || enabled == null) return null;
        if (fields[1].equals(enabled)) return new Field[]{fields[0], fields[2], fields[1]};
        if (fields[2].equals(enabled)) return fields;
        return null;
    }
}
