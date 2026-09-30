// Pinned loader shared by the forwarding control and AAudio proof builds.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace {
#ifdef CAUSTIC_AAUDIO
constexpr const char *kTag = "CausticAAudio";
#else
constexpr const char *kTag = "CausticAudioForward";
#endif
struct Dispatch {
    void (*start)(JNIEnv *, jclass);
    void (*pause)(JNIEnv *, jclass, jint);
    void (*stop)(JNIEnv *, jclass);
    void (*resample)(JNIEnv *, jclass, jboolean, jint);
    void (*buffers)(JNIEnv *, jclass, jint);
};
Dispatch dispatch{};
void *engine_handle;
void *engine_base;
jclass original_class;
std::atomic<bool> ready{false};
std::atomic<unsigned> log_count{0};
pthread_mutex_t init_mutex = PTHREAD_MUTEX_INITIALIZER;

void fail(JNIEnv *env, const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
    if (!env) return; // Recovery worker reports through status/logs, never JNI.
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message);
        env->DeleteLocalRef(type);
    }
}

bool begin(JNIEnv *env, const char *method, int first = 0, int second = 0) {
    if (!ready.load(std::memory_order_acquire)) {
        fail(env, "Audio forwarding used before initialization");
        return false;
    }
    // No callback logging. Bound lifecycle diagnostics even in long sessions.
    unsigned count = log_count.fetch_add(1, std::memory_order_relaxed);
    if (count < 512) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "tid=%d %s(%d,%d)",
                            gettid(), method, first, second);
    } else if (count == 512) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "Control log limit reached");
    }
    return true;
}

struct ImageSearch {
    char path[4096];
    unsigned count;
    bool truncated;
};
int find_image(dl_phdr_info *info, size_t, void *opaque) {
    auto *search = static_cast<ImageSearch *>(opaque);
    const char *path = info->dlpi_name;
    if (!path) return 0;
    const char *name = strrchr(path, '/');
    name = name ? name + 1 : path;
    if (strcmp(name, "libcaustic.so") && strcmp(name, "libcaustic_alt.so")) return 0;
    ++search->count;
    if (strlen(path) >= sizeof(search->path)) search->truncated = true;
    else strcpy(search->path, path);
    return 0;
}

bool resolve(void *handle, Dispatch *result, void **base) {
    const char *names[] = {
        "Java_com_singlecellsoftware_OpenSLIO_StartLoop",
        "Java_com_singlecellsoftware_OpenSLIO_PauseLoop",
        "Java_com_singlecellsoftware_OpenSLIO_StopLoop",
        "Java_com_singlecellsoftware_OpenSLIO_SetWantResample",
        "Java_com_singlecellsoftware_OpenSLIO_SetNumBuffers",
    };
    constexpr uintptr_t offsets[] = {0x2f4760, 0x2f4764, 0x2f476c, 0x2f4770, 0x2f4780};
    // Validate exact wrapper entry instructions as well as symbol ownership.
    constexpr uint32_t instructions[] = {0x140001b9, 0x2a0203e0, 0x1400026b,
                                         0x72001c5f, 0x2a0203e0};
    void *functions[5];
    for (unsigned i = 0; i < 5; ++i) {
        functions[i] = dlsym(handle, names[i]);
        Dl_info info{};
        if (!functions[i] || !dladdr(functions[i], &info)) return false;
        if (i == 0) *base = info.dli_fbase;
        if (info.dli_fbase != *base ||
            reinterpret_cast<uintptr_t>(functions[i]) !=
                    reinterpret_cast<uintptr_t>(*base) + offsets[i]) return false;
        uint32_t instruction;
        memcpy(&instruction, functions[i], sizeof(instruction));
        if (instruction != instructions[i]) return false;
    }
    result->start = reinterpret_cast<decltype(result->start)>(functions[0]);
    result->pause = reinterpret_cast<decltype(result->pause)>(functions[1]);
    result->stop = reinterpret_cast<decltype(result->stop)>(functions[2]);
    result->resample = reinterpret_cast<decltype(result->resample)>(functions[3]);
    result->buffers = reinterpret_cast<decltype(result->buffers)>(functions[4]);
    return true;
}
} // namespace

#ifdef CAUSTIC_AAUDIO
#include "caustic_aaudio.h"
#ifdef CAUSTIC_RECOVERY
#include "caustic_recovery.h"
#endif
#endif

#define JNI_METHOD(name) Java_com_singlecellsoftware_caustic_audio_AudioBackend_##name

extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(nativeLoadedLibraryPath)(JNIEnv *env, jclass) {
    ImageSearch search{};
    dl_iterate_phdr(find_image, &search);
    if (search.count != 1 || search.truncated || search.path[0] != '/') {
        fail(env, "Expected exactly one loaded Caustic engine with an absolute path");
        return nullptr;
    }
    return env->NewStringUTF(search.path);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeInitialize)(
        JNIEnv *env, jclass, jstring path, jclass owner) {
    if (!path || !owner) { fail(env, "Missing original library path/class"); return; }
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return;
    // NOLOAD is essential: the activity must already have loaded this exact image.
    void *handle = dlopen(chars, RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    env->ReleaseStringUTFChars(path, chars);
    if (!handle) { fail(env, "Original engine is not already loaded in this namespace"); return; }
    Dispatch candidate{};
    void *base = nullptr;
    if (!resolve(handle, &candidate, &base)) {
        dlclose(handle);
        fail(env, "Original engine JNI addresses/instructions do not match pinned ABI");
        return;
    }
    #ifdef CAUSTIC_AAUDIO
    if (!resolve_renderer(base)) {
        dlclose(handle);
        fail(env, "Original renderer instructions do not match pinned ABI");
        return;
    }
    #endif
    pthread_mutex_lock(&init_mutex);
    if (ready.load(std::memory_order_relaxed)) {
        bool same = engine_base == base && env->IsSameObject(original_class, owner);
        pthread_mutex_unlock(&init_mutex);
        dlclose(handle);
        if (!same) fail(env, "Cannot replace initialized audio forwarding engine/class");
        return;
    }
    jclass retained = static_cast<jclass>(env->NewGlobalRef(owner));
    if (!retained) {
        pthread_mutex_unlock(&init_mutex);
        dlclose(handle);
        return; // NewGlobalRef leaves the allocation exception pending.
    }
    dispatch = candidate;
    engine_handle = handle; // Keep the validated image and class alive for the process.
    engine_base = base;
    original_class = retained;
    ready.store(true, std::memory_order_release);
    pthread_mutex_unlock(&init_mutex);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Pinned audio bridge ready: original=%p", base);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(StartLoop)(JNIEnv *env, jclass) {
#ifdef CAUSTIC_AAUDIO
    if (!begin(env, "StartLoop")) return;
    AudioLock lock;
#ifdef CAUSTIC_RECOVERY
    prepare_output(env);
#else
    open_output(env); // Original StartLoop prepares; PauseLoop(0) starts playback.
#endif
#else
    if (begin(env, "StartLoop")) dispatch.start(env, original_class);
#endif
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(PauseLoop)(JNIEnv *env, jclass, jint paused) {
#ifdef CAUSTIC_AAUDIO
    if (!begin(env, "PauseLoop", paused)) return;
    AudioLock lock;
#ifdef CAUSTIC_RECOVERY
    if (paused) suspend_output(env); else resume_output(env);
#else
    if (paused) close_output(env); else start_output(env);
#endif
#else
    if (begin(env, "PauseLoop", paused)) dispatch.pause(env, original_class, paused);
#endif
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(StopLoop)(JNIEnv *env, jclass) {
#ifdef CAUSTIC_AAUDIO
    if (!begin(env, "StopLoop")) return;
    AudioLock lock;
#ifdef CAUSTIC_RECOVERY
    suspend_output(env);
#else
    close_output(env);
#endif
#else
    if (begin(env, "StopLoop")) dispatch.stop(env, original_class);
#endif
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(SetWantResample)(
        JNIEnv *env, jclass, jboolean enabled, jint frames) {
#ifdef CAUSTIC_AAUDIO
    // Device hints do not change the engine rate; AAudio must return 44100 Hz.
    begin(env, "SetWantResample (hint only)", enabled, frames);
#else
    if (begin(env, "SetWantResample", enabled, frames)) dispatch.resample(env, original_class, enabled, frames);
#endif
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(SetNumBuffers)(JNIEnv *env, jclass, jint count) {
#ifdef CAUSTIC_AAUDIO
    if (!begin(env, "SetNumBuffers", count)) return;
    AudioLock lock;
    if (count < 1 || count > 4) { fail(env, "Invalid audio preset"); return; }
#ifdef CAUSTIC_RECOVERY
    change_preset(env, count);
#else
    output.preset = count;
#endif
#else
    if (begin(env, "SetNumBuffers", count)) dispatch.buffers(env, original_class, count);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeHasAAudio)(JNIEnv *, jclass) {
#ifdef CAUSTIC_AAUDIO
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(nativeHasRecovery)(JNIEnv *, jclass) {
#ifdef CAUSTIC_RECOVERY
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nativeRouteChanged)(JNIEnv *, jclass) {
#ifdef CAUSTIC_RECOVERY
    if (!ready.load(std::memory_order_acquire)) return;
    AudioLock lock;
    route_changed_at = audio_nanos();
    ++route_generation;
    __android_log_print(ANDROID_LOG_INFO, kTag, "Output topology changed: generation=%u", route_generation);
#endif
}
