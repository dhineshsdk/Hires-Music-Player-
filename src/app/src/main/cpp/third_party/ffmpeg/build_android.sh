#!/usr/bin/env bash
# Builds FFmpeg as LGPL shared libraries (libavutil, libswresample, libavcodec, libavformat) for
# Android arm64-v8a, into ./out/{include,lib/arm64-v8a}. The engine links them dynamically
# (CLAUDE.md rule 5): never --enable-gpl, never --enable-nonfree, never static.
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk/26.3.11579264 ./build_android.sh [ffmpeg-tag]
# Only decoders and demuxers for the formats in decoders/FfmpegDecoder.hpp are enabled. Encoders,
# muxers, filters, network protocols and programs are off, which also keeps the .so files small.
set -euo pipefail

TAG="${1:-n7.1.1}"
API=29
ABI=arm64-v8a
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK (26.3.11579264)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
SRC="$HERE/build/ffmpeg-$TAG"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"

if [ ! -d "$SRC" ]; then
    mkdir -p "$HERE/build"
    git clone --depth 1 --branch "$TAG" https://github.com/FFmpeg/FFmpeg.git "$SRC"
fi

cd "$SRC"
make distclean >/dev/null 2>&1 || true

# SLIBNAME_WITH_MAJOR / SLIB_INSTALL_NAME give plain libavcodec.so names: Android's loader and
# Gradle's packaging do not handle versioned sonames. 16 KB page alignment is required on Android 15+.
./configure \
    --prefix="$OUT" --libdir="$OUT/lib/$ABI" \
    --target-os=android --arch=aarch64 --enable-cross-compile \
    --cc="$CC" --cxx="$CC++" --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --enable-shared --disable-static --disable-symver \
    --disable-programs --disable-doc --disable-avdevice --disable-avfilter --disable-swscale \
    --disable-network --disable-autodetect --disable-everything \
    --disable-debug --enable-small \
    --enable-protocol=file \
    --enable-demuxer=mov,ape,wv,mp3,ogg,matroska,aiff,w64,wav,flac,aac,caf,tta,tak \
    --enable-parser=aac,aac_latm,mpegaudio,vorbis,opus,flac,tak \
    --enable-decoder=alac,aac,aac_latm,ape,wavpack,mp3,mp3float,mp2float,vorbis,opus,flac,tta,tak,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,pcm_u8 \
    --extra-cflags="-fPIC -O2" \
    --extra-ldflags="-Wl,-z,max-page-size=16384" \
    SLIBNAME_WITH_VERSION='$(SLIBNAME)' SLIBNAME_WITH_MAJOR='$(SLIBNAME)' \
    LIB_INSTALL_EXTRA_CMD=: SLIB_INSTALL_NAME='$(SLIBNAME)' SLIB_INSTALL_LINKS=

make -j"$(nproc)"
make install

echo "FFmpeg $TAG installed to $OUT"
echo "License check (must say LGPL):"
"$TOOLCHAIN/bin/llvm-strings" "$OUT/lib/$ABI/libavcodec.so" | grep -m1 "libavcodec license" || true
