package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public final class HostUiBindingsTest {
    @Test
    public void anchoredBuilderNeedsFullConfirmationContract() {
        ClassLoader loader = getClass().getClassLoader();
        assertEquals(RenamedDialogBuilder.class.getName(), HostUiBindings.selectDialogBuilder(
                Arrays.asList(String.class.getName(), RenamedDialogBuilder.class.getName()), loader));
        assertNull(HostUiBindings.selectDialogBuilder(
                Collections.singletonList(IncompleteBuilder.class.getName()), loader));
    }

    @Test
    public void multipleMatchingBuildersAreNotPickedByEnumerationOrder() {
        assertNull(HostUiBindings.selectDialogBuilder(Arrays.asList(
                RenamedDialogBuilder.class.getName(), AnotherBuilder.class.getName()),
                getClass().getClassLoader()));
    }

    @Test
    public void hostWrapperOverridesPositiveActionAndIsPreferredToLibraryBase() {
        assertEquals(HostDialogWrapper.class.getName(), HostUiBindings.selectDialogWrapper(
                RenamedDialogBuilder.class.getName(), Arrays.asList(
                        AnotherBuilder.class.getName(), HostDialogWrapper.class.getName()),
                getClass().getClassLoader()));
        assertNull(HostUiBindings.selectDialogWrapper(RenamedDialogBuilder.class.getName(),
                Collections.singletonList(AnotherBuilder.class.getName()),
                getClass().getClassLoader()));
    }

    @Test
    public void wrapperAmbiguityAndUnrelatedBuildersAreRejected() {
        assertNull(HostUiBindings.selectDialogWrapper(RenamedDialogBuilder.class.getName(),
                Arrays.asList(HostDialogWrapper.class.getName(), OtherDialogWrapper.class.getName()),
                getClass().getClassLoader()));
        assertNull(HostUiBindings.selectDialogWrapper(AnotherBuilder.class.getName(),
                Collections.singletonList(HostDialogWrapper.class.getName()),
                getClass().getClassLoader()));
    }

    @Test
    public void selectedAndEnabledRolesDoNotDependOnAlphabeticalFieldOrder() throws Exception {
        Field[] fields = HostUiBindings.itemFields(
                RenamedItem.class, RenamedItem.class.getField("aEnabled"));
        assertEquals("text", fields[0].getName());
        assertEquals("zSelected", fields[1].getName());
        assertEquals("aEnabled", fields[2].getName());
        assertNull(HostUiBindings.itemFields(RenamedItem.class, null));
        assertNull(HostUiBindings.itemFields(
                RenamedItem.class, ForeignItem.class.getField("enabled")));
    }

    @Test
    public void officialItemFactoryDefaultsProveEnabledRoleAfterAdapterInlining() {
        assertEquals("aEnabled", HostUiBindings.defaultEnabledField(RenamedItem.class).getName());
    }

    public static class RenamedDialogBuilder {
        public RenamedDialogBuilder(Context context) {}
        public void setTitle(CharSequence text) {}
        public void setMessage(CharSequence text) {}
        public void setPositiveButton(CharSequence text, DialogInterface.OnClickListener action) {}
        public void setNegativeButton(CharSequence text, DialogInterface.OnClickListener action) {}
        public Dialog show() { return null; }
    }

    public static class AnotherBuilder extends RenamedDialogBuilder {
        public AnotherBuilder(Context context) { super(context); }
    }

    public static class HostDialogWrapper extends RenamedDialogBuilder {
        public HostDialogWrapper(Context context) { super(context); }
        @Override
        public void setPositiveButton(CharSequence text, DialogInterface.OnClickListener action) {}
    }

    public static class OtherDialogWrapper extends RenamedDialogBuilder {
        public OtherDialogWrapper(Context context) { super(context); }
        @Override
        public void setPositiveButton(CharSequence text, DialogInterface.OnClickListener action) {}
    }

    public static class IncompleteBuilder {
        public IncompleteBuilder(Context context) {}
        public Dialog show() { return null; }
    }

    public static final class RenamedItem {
        public int a, b, c, d;
        public Drawable icon;
        public String text;
        public boolean zSelected, aEnabled;
        public ArrayList<RenamedItem> children;

        public static class Builder {
            public boolean enabled;
            public void clear() { enabled = true; }
            public RenamedItem make() {
                RenamedItem item = new RenamedItem();
                item.aEnabled = enabled;
                return item;
            }
        }
    }

    public static final class ForeignItem {
        public boolean enabled;
    }
}
