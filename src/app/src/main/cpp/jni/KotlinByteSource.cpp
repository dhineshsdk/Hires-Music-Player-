#include "KotlinByteSource.hpp"
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <cstdio>
#include <new>

#ifdef __ANDROID__
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "KotlinByteSource", __VA_ARGS__)
#else
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace Audiophile::Jni {

namespace {

struct KotlinSource {
    JavaVM* vm = nullptr;
    jobject target = nullptr;       // global ref to the RemoteByteSource
    jmethodID sizeId = nullptr;
    jmethodID readAtId = nullptr;
    jmethodID closeId = nullptr;
};

// Attaches the calling native thread once and detaches it when the thread ends.
struct ThreadAttachment {
    JavaVM* vm = nullptr;
    ~ThreadAttachment() {
        if (vm != nullptr) vm->DetachCurrentThread();
    }
};

JNIEnv* EnvForThisThread(JavaVM* vm) noexcept {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    thread_local ThreadAttachment attachment;
#ifdef __ANDROID__
    const jint rc = vm->AttachCurrentThread(&env, nullptr);
#else
    const jint rc = vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
#endif
    if (rc != JNI_OK) return nullptr;
    attachment.vm = vm;
    return env;
}

bool ClearPending(JNIEnv* env, const char* what) noexcept {
    if (!env->ExceptionCheck()) return false;
    LOGE("%s threw", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

int64_t Size(void* user) {
    auto* s = static_cast<KotlinSource*>(user);
    JNIEnv* env = EnvForThisThread(s->vm);
    if (env == nullptr) return -1;
    const jlong size = env->CallLongMethod(s->target, s->sizeId);
    return ClearPending(env, "size") ? -1 : static_cast<int64_t>(size);
}

int64_t ReadAt(void* user, uint64_t offset, void* dst, size_t bytes) {
    auto* s = static_cast<KotlinSource*>(user);
    if (bytes == 0) return 0;
    JNIEnv* env = EnvForThisThread(s->vm);
    if (env == nullptr) return -1;
    jobject buffer = env->NewDirectByteBuffer(dst, static_cast<jlong>(bytes));
    if (buffer == nullptr) {
        ClearPending(env, "NewDirectByteBuffer");
        return -1;
    }
    const jint n = env->CallIntMethod(s->target, s->readAtId, static_cast<jlong>(offset), buffer);
    env->DeleteLocalRef(buffer);
    if (ClearPending(env, "readAt") || n < 0) return -1;
    return static_cast<int64_t>(n);
}

void Release(void* user) {
    auto* s = static_cast<KotlinSource*>(user);
    JNIEnv* env = EnvForThisThread(s->vm);
    if (env != nullptr) {
        env->CallVoidMethod(s->target, s->closeId);
        ClearPending(env, "close");
        env->DeleteGlobalRef(s->target);
    }
    delete s;
}

} // namespace

void* CreateKotlinByteSource(JNIEnv* env, jobject remoteByteSource) noexcept {
    auto* s = new (std::nothrow) KotlinSource();
    if (s == nullptr) return nullptr;
    jclass cls = env->GetObjectClass(remoteByteSource);
    s->sizeId = env->GetMethodID(cls, "size", "()J");
    s->readAtId = env->GetMethodID(cls, "readAt", "(JLjava/nio/ByteBuffer;)I");
    s->closeId = env->GetMethodID(cls, "close", "()V");
    env->DeleteLocalRef(cls);
    s->target = env->NewGlobalRef(remoteByteSource);
    if (s->sizeId == nullptr || s->readAtId == nullptr || s->closeId == nullptr || s->target == nullptr ||
        env->GetJavaVM(&s->vm) != JNI_OK) {
        ClearPending(env, "CreateKotlinByteSource");
        if (s->target != nullptr) env->DeleteGlobalRef(s->target);
        delete s;
        return nullptr;
    }
    return s;
}

const hrc_byte_source_callbacks& KotlinByteSourceCallbacks() noexcept {
    static const hrc_byte_source_callbacks callbacks{&Size, &ReadAt, &Release};
    return callbacks;
}

} // namespace Audiophile::Jni
