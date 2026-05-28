# Module entry must remain accessible to the libxposed framework loader.
-keep class xyz.melodylsp.codec.MelodyCodecLspEntry { *; }
-keep class xyz.melodylsp.codec.MelodyCodecLspEntry$* { *; }

# AIDL-generated stubs and the parcelable types we hand across the bridge.
-keep class xyz.melodylsp.codec.bridge.** { *; }

# Master-switch Activity (referenced via the launcher intent filter).
-keep class xyz.melodylsp.codec.ui.MasterSwitchActivity { *; }

# libxposed API surface — never strip the names we override.
-keep class io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

# androidx.preference is provided at runtime by the host. Don't try to inline.
-dontwarn androidx.preference.**
-keep class androidx.preference.** { *; }
-keep class androidx.lifecycle.** { *; }

# COUI library: optional reflection target inside the host. Don't warn if missing.
-dontwarn com.coui.appcompat.preference.**
