// See include/OrtShim.h. Uses ONNX Runtime's C API (headers in ort/, MIT licence).
//
// LEARN — the C API is a big table of function pointers ("OrtApi"). We find
// the one exported function, OrtGetApiBase, by name with dlsym, and it hands us
// the table. Every call returns NULL on success or an OrtStatus with an error.
#include "include/OrtShim.h"
#include "ort/onnxruntime_c_api.h"

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const OrtApi* api = NULL;
static OrtEnv* env = NULL;
static OrtSession* session = NULL;
static char* outNames[2] = {NULL, NULL};

#define CHECK(call)                                                        \
    do {                                                                   \
        OrtStatus* st_ = (call);                                           \
        if (st_) {                                                         \
            snprintf(err, (size_t)errLen, "%s", api->GetErrorMessage(st_)); \
            api->ReleaseStatus(st_);                                       \
            return -1;                                                     \
        }                                                                  \
    } while (0)

int mk_ort_open(const char* dylibPath, const char* modelPath, int threads, char* err, int errLen) {
    if (session) return 0;
    void* lib = dlopen(dylibPath, RTLD_NOW | RTLD_LOCAL);
    if (!lib) { snprintf(err, (size_t)errLen, "Couldn't load ONNX Runtime: %s", dlerror()); return -1; }
    typedef const OrtApiBase* (*GetBase)(void);
    GetBase getBase = (GetBase)dlsym(lib, "OrtGetApiBase");
    if (!getBase) { snprintf(err, (size_t)errLen, "ONNX Runtime is missing OrtGetApiBase"); return -1; }
    api = getBase()->GetApi(ORT_API_VERSION);
    if (!api) { snprintf(err, (size_t)errLen, "ONNX Runtime version mismatch"); return -1; }

    CHECK(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "metronome-killer", &env));
    OrtSessionOptions* opts = NULL;
    CHECK(api->CreateSessionOptions(&opts));
    CHECK(api->SetIntraOpNumThreads(opts, threads));
    CHECK(api->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL));
    // LEARN: by default ONNX Runtime keeps a big memory pool ("arena") and reuses
    // it for speed. For one song every few minutes, giving memory back matters
    // more: these two settings cut peak memory a lot, at almost no speed cost.
    CHECK(api->DisableCpuMemArena(opts));
    CHECK(api->DisableMemPattern(opts));
    CHECK(api->CreateSession(env, modelPath, opts, &session));
    api->ReleaseSessionOptions(opts);

    OrtAllocator* alloc = NULL;
    CHECK(api->GetAllocatorWithDefaultOptions(&alloc));
    for (size_t i = 0; i < 2; ++i) {
        char* name = NULL;
        CHECK(api->SessionGetOutputName(session, i, alloc, &name));
        outNames[i] = strdup(name);
        CHECK(api->AllocatorFree(alloc, name));
    }
    return 0;
}

int mk_ort_run(const float* audio, int64_t length, float** beat, float** down, int64_t* frames, char* err, int errLen) {
    if (!session) { snprintf(err, (size_t)errLen, "model not loaded"); return -1; }
    OrtMemoryInfo* mem = NULL;
    CHECK(api->CreateCpuMemoryInfo(OrtDeviceAllocator, OrtMemTypeDefault, &mem));
    int64_t shape[2] = {1, length};
    OrtValue* input = NULL;
    CHECK(api->CreateTensorWithDataAsOrtValue(mem, (void*)audio, (size_t)length * sizeof(float), shape, 2,
                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input));
    api->ReleaseMemoryInfo(mem);

    const char* inNames[1] = {"audio"};
    OrtValue* outs[2] = {NULL, NULL};
    CHECK(api->Run(session, NULL, inNames, (const OrtValue* const*)&input, 1, (const char* const*)outNames, 2, outs));
    api->ReleaseValue(input);

    float* results[2] = {NULL, NULL};
    size_t count = 0;
    for (int i = 0; i < 2; ++i) {
        OrtTensorTypeAndShapeInfo* info = NULL;
        CHECK(api->GetTensorTypeAndShape(outs[i], &info));
        CHECK(api->GetTensorShapeElementCount(info, &count));
        api->ReleaseTensorTypeAndShapeInfo(info);
        float* data = NULL;
        CHECK(api->GetTensorMutableData(outs[i], (void**)&data));
        results[i] = (float*)malloc(count * sizeof(float));
        memcpy(results[i], data, count * sizeof(float));
        api->ReleaseValue(outs[i]);
    }
    *beat = results[0];
    *down = results[1];
    *frames = (int64_t)count;
    return 0;
}

void mk_ort_free(float* p) { free(p); }
