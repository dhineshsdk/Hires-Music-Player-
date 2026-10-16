# Keep JNI entry points referenced from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}
