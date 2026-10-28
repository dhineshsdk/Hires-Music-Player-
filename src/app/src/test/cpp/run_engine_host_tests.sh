#!/usr/bin/env bash
# Host tests for the C API and the engine (no phone): API contract, the engine end to end through
# ManualOutput, and real files decoded by libFLAC and (when installed) FFmpeg, compared with the
# ffmpeg CLI's own PCM. Runs under ASan+UBSan, then again under TSan.
# Needs: g++, libflac-dev, ffmpeg; optional libavformat-dev libavcodec-dev libavutil-dev.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cpp="$here/../../main/cpp"
out="${1:-$(mktemp -d)}"
mkdir -p "$out"

srcs=("$cpp/core/PlaybackEngine.cpp" "$cpp/core/HiresCore.cpp" "$cpp/decoders/WavDecoder.cpp"
      "$cpp/decoders/FlacDecoder.cpp" "$cpp/decoders/DsdDecoder.cpp" "$cpp/decoders/ReadAheadSource.cpp")
inc=(-I"$cpp/core" -I"$cpp/dsp" -I"$cpp/decoders")
libs=(-lFLAC)
defs=()
if pkg-config --exists libavformat libavcodec libavutil 2>/dev/null; then
    srcs+=("$cpp/decoders/FfmpegDecoder.cpp")
    defs+=(-DHIRES_HAVE_FFMPEG=1)
    read -r -a ff <<< "$(pkg-config --cflags --libs libavformat libavcodec libavutil)"
    libs+=("${ff[@]}")
fi
warn=(-Wall -Wextra -Werror -fno-exceptions -pthread)

# Fixtures and reference PCM from the ffmpeg CLI, independent of our decoders.
cases=()
ffmpeg -v error -y -f lavfi -i "anoisesrc=d=2:c=pink:r=96000:a=0.7:seed=11" -af aformat=channel_layouts=stereo \
    -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 "$out/e24.flac"
ffmpeg -v error -y -i "$out/e24.flac" -f s24le -c:a pcm_s24le "$out/e24.raw"
cases+=("$out/e24.flac" "$out/e24.raw" "libFLAC")
if [[ ${#defs[@]} -gt 0 ]]; then
    ffmpeg -v error -y -f lavfi -i "anoisesrc=d=2:c=pink:r=44100:a=0.7:seed=12" -af aformat=channel_layouts=stereo \
        -sample_fmt s16p -c:a alac "$out/e16.m4a"
    ffmpeg -v error -y -i "$out/e16.m4a" -f s16le -c:a pcm_s16le "$out/e16.raw"
    cases+=("$out/e16.m4a" "$out/e16.raw" "FFmpeg")
fi

for san in "address,undefined" "thread"; do
    tag="${san%%,*}"
    gcc -std=c11 -Wall -Wextra -Werror -pedantic -fsanitize="$san" -I"$cpp/core" -c "$cpp/core/hires_core_abi.c" -o "$out/abi_$tag.o"
    g++ -std=c++20 -O1 -g "${warn[@]}" -Wno-tsan -fsanitize="$san" -I"$cpp/core" \
        "$here/hires_core_api_test.cpp" "$out/abi_$tag.o" -o "$out/api_$tag"
    g++ -std=c++20 -O1 -g "${warn[@]}" -Wno-tsan -fsanitize="$san" "${defs[@]}" "${inc[@]}" \
        "$here/hires_engine_test.cpp" "${srcs[@]}" "$out/abi_$tag.o" "${libs[@]}" -o "$out/engine_$tag"
    echo "== $san"
    "$out/api_$tag"
    "$out/engine_$tag" "${cases[@]}" 2> "$out/engine_$tag.log"
done
echo "all engine host tests passed"
