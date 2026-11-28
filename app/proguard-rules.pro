# ─────────────────────────────────────────────────────────────────────────────
# R8 rules (release builds only).
#
# LEARN: R8 shrinks the app by deleting code nothing calls and renaming what's
# left to short names ("a", "b"...). That breaks code that finds classes BY NAME
# at runtime ("reflection") or from C++ (JNI), because R8 can't see those uses.
# These "-keep" lines protect exactly those parts. Everything else (Compose,
# Kotlin...) gets shrunk, which is where most of the savings come from.
# ─────────────────────────────────────────────────────────────────────────────

# Our own code is small; keep it whole so saved JSON/enum names and the JNI
# bridge (NativeEngine's external funs, looked up by name from jni.cpp) stay safe.
-keep class com.metronomekiller.app.** { *; }
