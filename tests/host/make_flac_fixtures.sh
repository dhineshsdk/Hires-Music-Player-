#!/usr/bin/env bash
# Writes the FLAC test inputs and their reference PCM into the directory given as $1.
# The reference PCM comes from ffmpeg's FLAC encoder and decoder, independent of libFLAC.
set -euo pipefail

out="${1:?usage: make_flac_fixtures.sh <output-dir>}"
mkdir -p "$out"
cd "$out"

ffmpeg -v error -y -f lavfi -i "anoisesrc=d=4:c=pink:r=44100:a=0.7:seed=7" \
    -af aformat=channel_layouts=stereo -sample_fmt s16 -c:a flac t16.flac
ffmpeg -v error -y -i t16.flac -f s16le -c:a pcm_s16le t16.raw

ffmpeg -v error -y -f lavfi -i "anoisesrc=d=4:c=pink:r=96000:a=0.7:seed=7" \
    -af aformat=channel_layouts=stereo -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 t24.flac
ffmpeg -v error -y -i t24.flac -f s24le -c:a pcm_s24le t24.raw

ffmpeg -v error -y -f lavfi -i "anoisesrc=d=3:c=white:r=192000:a=0.9:seed=3" \
    -af aformat=channel_layouts=stereo -sample_fmt s32 -c:a flac -bits_per_raw_sample 24 h.flac
ffmpeg -v error -y -i h.flac -f s24le -c:a pcm_s24le h.raw
