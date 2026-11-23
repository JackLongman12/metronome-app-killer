// ─────────────────────────────────────────────────────────────────────────────
// A tiny C wrapper around ONNX Runtime, for running the beat-tracking model.
//
// LEARN — why "dlopen": ONNX Runtime is a 48 MB library. Most people use the
// metronome without ever analysing a song, so the app doesn't ship it. It's
// downloaded the first time you analyse a song, and loaded then with dlopen
// ("open this library file now, while the program is running").
// ─────────────────────────────────────────────────────────────────────────────
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Loads the library and the model once. 0 = ok; otherwise err holds a message. */
int mk_ort_open(const char* dylibPath, const char* modelPath, int threads, char* err, int errLen);

/**
 * Runs one chunk of 22050 Hz mono audio. On success *beat and *down point to
 * *frames scores each (50 per second); free them with mk_ort_free.
 */
int mk_ort_run(const float* audio, int64_t length, float** beat, float** down, int64_t* frames, char* err, int errLen);

void mk_ort_free(float* p);

/** Frees the model and its working memory (call after each song). */
void mk_ort_close(void);

#ifdef __cplusplus
}
#endif
