#!/usr/bin/env bash
# Host tests for WP1.3 / WP1.4: ReadAheadSource, FfmpegDecoder (through CallbackSource + ReadAheadSource),
# and the JNI byte-source bridge against a real JVM. Needs: g++, ffmpeg CLI + dev libraries
# (libavformat-dev libavcodec-dev libavutil-dev libswresample-dev), a JDK.
# FFmpeg here is the system one; the Android build uses third_party/ffmpeg/build_android.sh.
set -euo pipefail
cd "$(dirname "$0")"
D=../../main/cpp/decoders; C=../../main/cpp/core; J=../../main/cpp/jni
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FF="$(pkg-config --cflags --libs libavformat libavcodec libavutil)"
FLAGS="-std=c++20 -Wall -Wextra -Werror -fno-exceptions -pthread"

g++ $FLAGS -O1 -g -fsanitize=address,undefined -I$D read_ahead_source_test.cpp $D/ReadAheadSource.cpp -o "$W/ras_asan" && "$W/ras_asan"
g++ $FLAGS -O1 -g -fsanitize=thread -I$D read_ahead_source_test.cpp $D/ReadAheadSource.cpp -o "$W/ras_tsan" && "$W/ras_tsan"

g++ $FLAGS -O1 -g -fsanitize=address,undefined -I$D -I$C ffmpeg_decoder_test.cpp $D/FfmpegDecoder.cpp $D/ReadAheadSource.cpp $FF -o "$W/ffdec"
g++ $FLAGS -O1 -g -fsanitize=address,undefined -I$D -I$C gapless_decode_test.cpp $D/FfmpegDecoder.cpp $FF -o "$W/gapless"
ff() { ffmpeg -v error -y "$@"; }
cd "$W"
src() { ff -f lavfi -i "anoisesrc=d=$1:c=pink:r=$2:a=0.7:seed=$3" -af "aformat=channel_layouts=stereo$4" "${@:5}"; }
src 5 44100 7 ",aformat=sample_fmts=s16" -c:a alac a16.m4a;  ff -i a16.m4a -f s16le a16.raw
src 4 96000 9 ",aformat=sample_fmts=s32" -c:a alac a24.m4a;  ff -i a24.m4a -f s24le a24.raw
src 4 44100 5 "" -c:a libmp3lame -b:a 192k m.mp3;             ff -i m.mp3 -f f32le m.raw
src 4 48000 5 "" -c:a aac -b:a 192k aac.m4a;                  ff -i aac.m4a -f f32le aac.raw
src 4 44100 6 "" -c:a libvorbis v.ogg;                        ff -i v.ogg -f f32le v.raw
src 4 48000 6 "" -c:a libopus o.opus;                         ff -i o.opus -f f32le o.raw
src 4 88200 8 ",aformat=sample_fmts=s32" -c:a wavpack -bits_per_raw_sample 24 w.wv;   ff -i w.wv -f s24le w.raw
src 4 192000 2 ",aformat=sample_fmts=s32" -c:a pcm_s24be x.aiff; ff -i x.aiff -f s24le x.raw
src 3 44100 3 ",aformat=sample_fmts=s16" -c:a pcm_s16le x.w64;   ff -i x.w64 -f s16le x16.raw
#                       file    ref     bytes bits exactSeek container rate ch   (containers: 3 W64, 4 AIFF, 8 MP4, 10 WavPack, 11 MP3, 12 Ogg)
"$W/ffdec" a16.m4a a16.raw 2 16 1 8  44100 2
"$W/ffdec" a24.m4a a24.raw 3 24 1 8  96000 2
"$W/ffdec" m.mp3   m.raw   4 32 0 11 44100 2
"$W/ffdec" aac.m4a aac.raw 4 32 0 8  48000 2
"$W/ffdec" v.ogg   v.raw   4 32 0 12 44100 2
"$W/ffdec" o.opus  o.raw   4 32 0 12 48000 2
"$W/ffdec" w.wv    w.raw   3 24 1 10 88200 2
"$W/ffdec" x.aiff  x.raw   3 24 1 4  192000 2
"$W/ffdec" x.w64   x16.raw 2 16 1 3  44100 2

# Gapless: the tag parsers, and exact length/alignment of lossy files (no encoder delay or padding).
SRC='aevalsrc=0.3*sin(2*PI*(300*t+2500*t*t))|0.3*sin(2*PI*(500*t+1800*t*t)):s=44100:d=1'
ff -f lavfi -i "$SRC" -f f32le chirp.raw
ff -f lavfi -i "$SRC" -c:a libmp3lame -b:a 192k gap.mp3
ff -f lavfi -i "$SRC" -c:a aac -b:a 192k gap.m4a
# Priming that only the iTunSMPB tag describes (no edit list), as iTunes-style files can be.
ff -f lavfi -i "$SRC" -c:a aac -b:a 192k -use_editlist 0 -movflags +use_metadata_tags \
   -metadata "iTunSMPB= 00000000 00000400 000003FC 000000000000AC44 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000" gap_smpb.m4a
"$W/gapless"
"$W/gapless" gap.mp3 chirp.raw
"$W/gapless" gap.m4a chirp.raw
"$W/gapless" gap_smpb.m4a chirp.raw
cd - >/dev/null

# JNI bridge against a real JVM.
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which javac)")")")}"
mkdir -p "$W/jni"
javac -d "$W/jni" jni_byte_source/com/audiophile/engine/library/source/RemoteByteSource.java jni_byte_source/Mem.java
g++ $FLAGS -fsanitize=address,undefined -I$J -I$C -I$D -I"$JH/include" -I"$JH/include/linux" \
    jni_byte_source/jni_byte_source_test.cpp $J/KotlinByteSource.cpp -L"$JH/lib/server" -ljvm -Wl,-rpath,"$JH/lib/server" -o "$W/jni/jni_test"
(cd "$W/jni" && ASAN_OPTIONS=detect_leaks=0 ./jni_test)
echo "all WP1 host tests passed"
