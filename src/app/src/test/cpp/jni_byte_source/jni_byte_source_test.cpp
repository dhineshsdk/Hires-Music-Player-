// Host test of KotlinByteSource against a real JVM. Build/run: see run line in handoff.
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>
#include "KotlinByteSource.hpp"
#include "CallbackSource.hpp"
using namespace Audiophile;
static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); ++failures; } } while (0)
int main() {
    JavaVM* vm; JNIEnv* env;
    JavaVMInitArgs args{}; JavaVMOption opt{}; opt.optionString = const_cast<char*>("-Djava.class.path=.");
    args.version = JNI_VERSION_1_8; args.nOptions = 1; args.options = &opt;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) return 2;
    jclass cls = env->FindClass("Mem"); jmethodID ctor = env->GetMethodID(cls, "<init>", "(I)V");
    jobject obj = env->NewObject(cls, ctor, 100000);
    void* user = Jni::CreateKotlinByteSource(env, obj);
    CHECK(user != nullptr);
    env->DeleteLocalRef(obj);
    jfieldID closedId = env->GetStaticFieldID(cls, "closed", "I");
    {
        Decoders::CallbackSource src(Jni::KotlinByteSourceCallbacks(), user);   // main thread (already attached)
        CHECK(src.Size() == 100000);
        std::vector<uint8_t> buf(5000);
        CHECK(src.ReadAt(1234, buf.data(), buf.size()) == 5000);
        bool ok = true;
        for (size_t i = 0; i < buf.size(); ++i) ok &= buf[i] == static_cast<uint8_t>((1234 + i) * 31 + 7);
        CHECK(ok);
        CHECK(src.ReadAt(99000, buf.data(), buf.size()) == 1000);
        CHECK(src.ReadAt(100000, buf.data(), buf.size()) == 0);
        CHECK(src.ReadAt(13, buf.data(), buf.size()) == -1);   // Kotlin threw
        CHECK(src.ReadAt(17, buf.data(), buf.size()) == -1);   // Kotlin reported an error
        CHECK(src.ReadAt(0, buf.data(), 10) == 10);            // still usable after errors
        // A native thread that was never attached, like the decoder thread.
        int64_t n = 0;
        std::thread t([&] { std::vector<uint8_t> b(777); n = src.ReadAt(500, b.data(), b.size()); });
        t.join();
        CHECK(n == 777);
        CHECK(env->GetStaticIntField(cls, closedId) == 0);
    }
    CHECK(env->GetStaticIntField(cls, closedId) == 1);   // release exactly once
    std::printf(failures ? "FAILED\n" : "kotlin_byte_source jni test OK\n");
    return failures;
}
