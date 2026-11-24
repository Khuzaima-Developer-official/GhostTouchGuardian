# Ghost Touch Guardian v4.0 — Proguard rules

# Keep all classes in our package (small codebase, safe to keep all)
-keep class com.antigravity.ghosttouchguardian.** { *; }

# Keep View subclasses used by WindowManager (reflection instantiation)
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Keep BroadcastReceivers and Services declared in Manifest
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service

# Keep data classes (Ctx, Vrd, Z, Stat) — Kotlin data classes use reflection for toString/equals
-keepclassmembers class com.antigravity.ghosttouchguardian.Ctx   { *; }
-keepclassmembers class com.antigravity.ghosttouchguardian.Vrd   { *; }
-keepclassmembers class com.antigravity.ghosttouchguardian.Z     { *; }

# AndroidX core
-dontwarn androidx.**
-keep class androidx.core.app.** { *; }

# Remove all logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
