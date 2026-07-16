# Keryx R8 rules — prepared for minified release builds.
# NOTE: minification is currently DISABLED in build.gradle.kts; these keeps have not yet been
# verified on-device. Before flipping isMinifyEnabled=true, install a minified build and exercise
# login, sync, send/receive, media, streaming, and notifications end to end.

# --- kotlinx-serialization (reflection over @Serializable companions/serializers) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class chat.keryx.app.**$$serializer { *; }
-keepclassmembers class chat.keryx.app.** { *** Companion; }
-keepclasseswithmembers class chat.keryx.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Trixnity (Matrix SDK): heavy serialization + module loading by name ---
-keep class net.folivo.trixnity.** { *; }
-dontwarn net.folivo.trixnity.**

# --- Ktor client / OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**

# --- Coroutines debug metadata ---
-dontwarn kotlinx.coroutines.debug.**

# --- markdown renderer (intellij-markdown parser uses reflection-free API; silence notes) ---
-dontwarn org.intellij.markdown.**

# --- Strip info/debug logging from release (hot-path emission logs are also KLog-gated on
# --- BuildConfig.DEBUG; this removes the remaining android.util.Log.i/d/v call sites and lets
# --- R8 drop their string-building too) ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- JNA (Trixnity olm/E2EE binds libolm through it): libjnidispatch.so resolves JNA's Java
# --- classes and fields BY NAME over JNI (e.g. com.sun.jna.Pointer.peer), so nothing in JNA may
# --- be renamed or stripped. Caught live on the first minified install: session restore died in
# --- OlmAccount.unpickle with "Can't obtain peer field ID for class com.sun.jna.Pointer". ---
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# --- Bundled SQLite (Trixnity room repository JNI) ---
-keep class androidx.sqlite.driver.bundled.** { *; }
-dontwarn androidx.sqlite.driver.bundled.**

# --- UnifiedPush connector (broadcast receivers resolved by name) ---
-keep class org.unifiedpush.android.connector.** { *; }
-dontwarn org.unifiedpush.android.connector.**
