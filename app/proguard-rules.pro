# ─────────────────────────────────────────────────────────────────────────────
# R8 rules (release builds only).
#
# LEARN: R8 shrinks the app by deleting code nothing calls and renaming what's
# left to short names ("a", "b"...). That breaks code that finds classes BY NAME
# at runtime ("reflection") or from C++ (JNI), because R8 can't see those uses.
# These "-keep" lines protect exactly those parts. Everything else (Compose,
# Kotlin, Media3...) gets shrunk, which is where most of the savings come from.
# ─────────────────────────────────────────────────────────────────────────────

# Our own code is small; keep it whole so saved JSON/enum names and the JNI
# bridge (NativeEngine's external funs, looked up by name from jni.cpp) stay safe.
-keep class com.metronomekiller.app.** { *; }

# NewPipe Extractor runs YouTube's JavaScript with Rhino, which loads classes by name.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.grack.nanojson.** { *; }

# ONNX Runtime's C++ side creates and calls these Java classes by name.
-keep class ai.onnxruntime.** { *; }

# Parts of those libraries reference desktop-Java classes that don't exist on
# Android; they're never used on a phone, so don't fail the build over them.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.lang.model.**
-dontwarn org.jspecify.**
