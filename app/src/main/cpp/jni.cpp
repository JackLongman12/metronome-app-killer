// ─────────────────────────────────────────────────────────────────────────────
// JNI bridge: how Kotlin talks to C++.
//
// Kotlin declares functions with the `external` keyword (see NativeEngine.kt).
// When Kotlin calls one, the Android runtime looks for a C function with a very
// specific name: Java_<package with _ instead of .>_<class>_<method>.
// The JNI_FN macro below builds those names so we don't typo them.
//
// Every JNI function receives JNIEnv* (a toolbox for touching Java objects,
// e.g. reading arrays) and jobject (the Kotlin object the call came from).
// Plain numbers (jint, jlong, jdouble...) map straight to C++ types.
// ─────────────────────────────────────────────────────────────────────────────
#include <jni.h>
#include <vector>
#include "ClickEngine.h"

// One engine for the whole process. A function-local static is created the first
// time it's used and lives until the app process dies — no manual new/delete.
static ClickEngine* engine() {
    static ClickEngine e;
    return &e;
}

#define JNI_FN(name) Java_com_metronomekiller_app_engine_NativeEngine_##name

extern "C" {  // C linkage: stops C++ from "mangling" the names, so JNI can find them

JNIEXPORT jboolean JNICALL JNI_FN(nStart)(JNIEnv*, jobject, jboolean clicks) { return engine()->start(clicks); }
JNIEXPORT void JNICALL JNI_FN(nStop)(JNIEnv*, jobject) { engine()->stop(); }
JNIEXPORT jboolean JNICALL JNI_FN(nIsRunning)(JNIEnv*, jobject) { return engine()->isRunning(); }
JNIEXPORT void JNICALL JNI_FN(nSetClicksEnabled)(JNIEnv*, jobject, jboolean on) { engine()->setClicksEnabled(on); }
JNIEXPORT void JNICALL JNI_FN(nPreview)(JNIEnv*, jobject, jint sound) { engine()->preview(sound); }
JNIEXPORT void JNICALL JNI_FN(nSetTempo)(JNIEnv*, jobject, jdouble bpm) { engine()->setTempo(bpm); }
JNIEXPORT void JNICALL JNI_FN(nSetSubdivision)(JNIEnv*, jobject, jint s) { engine()->setSubdivision(s); }
JNIEXPORT void JNICALL JNI_FN(nSetSound)(JNIEnv*, jobject, jint s) { engine()->setSound(s); }
JNIEXPORT void JNICALL JNI_FN(nSetLastBeatFraction)(JNIEnv*, jobject, jdouble f) { engine()->setLastBeatFraction(f); }
JNIEXPORT void JNICALL JNI_FN(nSetVolume)(JNIEnv*, jobject, jfloat v) { engine()->setVolume(v); }

JNIEXPORT void JNICALL JNI_FN(nSetBeats)(JNIEnv* env, jobject, jintArray states) {
    // Java arrays live in the garbage-collected heap, so we copy them out
    // into a normal C++ vector before using them.
    const jsize n = env->GetArrayLength(states);
    std::vector<int32_t> buf(n);
    env->GetIntArrayRegion(states, 0, n, reinterpret_cast<jint*>(buf.data()));
    engine()->setBeats(buf.data(), n);
}

JNIEXPORT jlong JNICALL JNI_FN(nAudibleBeat)(JNIEnv*, jobject, jlong nowNs, jlong offsetNs) {
    return engine()->audibleBeat(nowNs, offsetNs);
}

JNIEXPORT jlong JNICALL JNI_FN(nNearestBeatOffset)(JNIEnv*, jobject, jlong tapNs) {
    return engine()->nearestBeatOffset(tapNs);
}

}  // extern "C"
