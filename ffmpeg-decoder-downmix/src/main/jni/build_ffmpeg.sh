#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
#
# Reproducible FFmpeg static-library builder for the FOX.TV patched Media3
# decoder. The upstream helper was adapted to use an immutable source commit,
# out-of-tree per-ABI builds and a fixed codec surface.

set -euo pipefail

readonly EXPECTED_FFMPEG_TAG="n7.1.5"
readonly EXPECTED_FFMPEG_COMMIT="3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587"
readonly EXPECTED_NDK_REVISION="28.2.13676358"
readonly ANDROID_API="24"
readonly ABIS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")
readonly DECODERS=(
  "aac" "mp3" "ac3" "eac3" "truehd" "dca" "vorbis" "opus"
  "amrnb" "amrwb" "flac" "alac" "pcm_mulaw" "pcm_alaw" "h264" "hevc"
)
readonly JOBS="${FOX_FFMPEG_JOBS:-1}"

usage() {
  printf 'Usage: %s <ffmpeg-source-dir> <new-build-root> <android-ndk-dir>\n' "$0" >&2
  printf 'Required source: %s (%s)\n' "$EXPECTED_FFMPEG_TAG" "$EXPECTED_FFMPEG_COMMIT" >&2
}

if [[ "$#" -ne 3 ]]; then
  usage
  exit 64
fi

SOURCE_DIR="$(realpath "$1")"
BUILD_ROOT_INPUT="$2"
NDK_DIR="$(realpath "$3")"

if [[ ! "$JOBS" =~ ^[1-9][0-9]*$ ]]; then
  printf 'FOX_FFMPEG_JOBS must be a positive integer, got %q\n' "$JOBS" >&2
  exit 64
fi
if [[ ! -x "$SOURCE_DIR/configure" ]]; then
  printf 'Invalid FFmpeg source tree: %s/configure is not executable\n' "$SOURCE_DIR" >&2
  exit 66
fi
if [[ ! -d "$SOURCE_DIR/.git" ]]; then
  printf 'FFmpeg source must be a Git checkout so its immutable revision can be verified\n' >&2
  exit 66
fi

ACTUAL_COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$EXPECTED_FFMPEG_COMMIT" ]]; then
  printf 'Wrong FFmpeg revision: %s; expected %s (%s)\n' \
    "$ACTUAL_COMMIT" "$EXPECTED_FFMPEG_COMMIT" "$EXPECTED_FFMPEG_TAG" >&2
  exit 65
fi
if [[ -n "$(git -C "$SOURCE_DIR" status --porcelain --untracked-files=normal)" ]]; then
  printf 'FFmpeg checkout is dirty; use an unmodified %s checkout\n' "$EXPECTED_FFMPEG_COMMIT" >&2
  exit 65
fi

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *)
    printf 'Unsupported host OS: %s\n' "$(uname -s)" >&2
    exit 69
    ;;
esac

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN/sysroot" || ! -x "$TOOLCHAIN/bin/llvm-ar" ]]; then
  printf 'Invalid Android NDK/toolchain: %s\n' "$TOOLCHAIN" >&2
  exit 66
fi

NDK_SOURCE_PROPERTIES="$NDK_DIR/source.properties"
if [[ ! -f "$NDK_SOURCE_PROPERTIES" ]]; then
  printf 'Android NDK has no source.properties: %s\n' "$NDK_SOURCE_PROPERTIES" >&2
  exit 66
fi
ACTUAL_NDK_REVISION=""
while IFS='=' read -r key value; do
  key="${key//[[:space:]]/}"
  if [[ "$key" == "Pkg.Revision" ]]; then
    ACTUAL_NDK_REVISION="${value//[[:space:]]/}"
    break
  fi
done < "$NDK_SOURCE_PROPERTIES"
if [[ "$ACTUAL_NDK_REVISION" != "$EXPECTED_NDK_REVISION" ]]; then
  printf 'Wrong Android NDK revision: %s; expected %s\n' \
    "${ACTUAL_NDK_REVISION:-missing}" "$EXPECTED_NDK_REVISION" >&2
  exit 65
fi

BUILD_PARENT="$(realpath -m "$(dirname "$BUILD_ROOT_INPUT")")"
BUILD_NAME="$(basename "$BUILD_ROOT_INPUT")"
mkdir -p "$BUILD_PARENT"
BUILD_ROOT="$BUILD_PARENT/$BUILD_NAME"
if [[ -e "$BUILD_ROOT" ]]; then
  printf 'Build root already exists; refusing to overwrite: %s\n' "$BUILD_ROOT" >&2
  exit 73
fi

STAGING_ROOT="$BUILD_PARENT/.${BUILD_NAME}.staging-$$"
if [[ -e "$STAGING_ROOT" ]]; then
  printf 'Staging path already exists: %s\n' "$STAGING_ROOT" >&2
  exit 73
fi
mkdir -p "$STAGING_ROOT"

printf 'Building FFmpeg %s (%s) with NDK %s, API %s, jobs=%s\n' \
  "$EXPECTED_FFMPEG_TAG" "$EXPECTED_FFMPEG_COMMIT" "$NDK_DIR" "$ANDROID_API" "$JOBS"
printf 'A failed build leaves staging diagnostics at %s and never replaces %s\n' \
  "$STAGING_ROOT" "$BUILD_ROOT"

common_options=(
  "--target-os=android"
  "--enable-cross-compile"
  "--enable-static"
  "--disable-shared"
  "--enable-pic"
  "--disable-doc"
  "--disable-programs"
  "--disable-network"
  "--disable-autodetect"
  "--disable-everything"
  "--disable-avdevice"
  "--disable-avformat"
  "--disable-swscale"
  "--disable-postproc"
  "--disable-avfilter"
  "--disable-symver"
  "--disable-vulkan"
  "--disable-v4l2-m2m"
  "--enable-avcodec"
  "--enable-avutil"
  "--enable-swresample"
  "--enable-encoder=ac3"
  "--enable-pthreads"
  "--extra-ldexeflags=-pie"
  "--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)
for decoder in "${DECODERS[@]}"; do
  common_options+=("--enable-decoder=$decoder")
done

build_abi() {
  local abi="$1"
  local arch cpu target
  local abi_options=()

  case "$abi" in
    armeabi-v7a)
      arch="arm"
      cpu="armv7-a"
      target="armv7a-linux-androideabi${ANDROID_API}"
      abi_options+=("--extra-cflags=-fPIC -fstack-protector-strong -march=armv7-a -mfloat-abi=softfp")
      abi_options+=("--extra-ldflags=-Wl,--fix-cortex-a8 -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
      ;;
    arm64-v8a)
      arch="aarch64"
      cpu="armv8-a"
      target="aarch64-linux-android${ANDROID_API}"
      abi_options+=("--extra-cflags=-fPIC -fstack-protector-strong")
      ;;
    x86)
      arch="x86"
      cpu="i686"
      target="i686-linux-android${ANDROID_API}"
      abi_options+=("--disable-asm" "--extra-cflags=-fPIC -fstack-protector-strong")
      ;;
    x86_64)
      arch="x86_64"
      cpu="x86-64"
      target="x86_64-linux-android${ANDROID_API}"
      abi_options+=("--disable-asm" "--extra-cflags=-fPIC -fstack-protector-strong")
      ;;
    *)
      printf 'Unsupported ABI: %s\n' "$abi" >&2
      return 64
      ;;
  esac

  local abi_root="$STAGING_ROOT/$abi"
  local build_dir="$abi_root/build"
  local install_dir="$abi_root/install"
  mkdir -p "$build_dir" "$install_dir"

  printf '\n==> Configuring %s\n' "$abi"
  (
    cd "$build_dir"
    "$SOURCE_DIR/configure" \
      "${common_options[@]}" \
      "${abi_options[@]}" \
      "--prefix=$install_dir" \
      "--libdir=$install_dir/lib" \
      "--incdir=$install_dir/include" \
      "--arch=$arch" \
      "--cpu=$cpu" \
      "--cc=$TOOLCHAIN/bin/${target}-clang" \
      "--cxx=$TOOLCHAIN/bin/${target}-clang++" \
      "--ar=$TOOLCHAIN/bin/llvm-ar" \
      "--nm=$TOOLCHAIN/bin/llvm-nm" \
      "--ranlib=$TOOLCHAIN/bin/llvm-ranlib" \
      "--strip=$TOOLCHAIN/bin/llvm-strip" \
      "--sysroot=$TOOLCHAIN/sysroot"
    make -j"$JOBS"
    make install-libs install-headers
  )

  local components_file="$build_dir/config_components.h"
  if [[ ! -f "$components_file" ]]; then
    printf 'Missing generated codec configuration for %s: %s\n' \
      "$abi" "$components_file" >&2
    return 70
  fi
  local decoder macro
  for decoder in "${DECODERS[@]}"; do
    macro="#define CONFIG_${decoder^^}_DECODER 1"
    if ! grep -Fqx "$macro" "$components_file"; then
      printf 'Required decoder was not enabled for %s: %s\n' "$abi" "$decoder" >&2
      return 70
    fi
  done
  if ! grep -Fqx '#define CONFIG_AC3_ENCODER 1' "$components_file"; then
    printf 'Required AC-3 encoder was not enabled for %s\n' "$abi" >&2
    return 70
  fi

  for library in avcodec avutil swresample; do
    if [[ ! -f "$install_dir/lib/lib${library}.a" ]]; then
      printf 'Missing output for %s: lib%s.a\n' "$abi" "$library" >&2
      return 70
    fi
  done
  if [[ ! -f "$install_dir/include/libavcodec/avcodec.h" ]]; then
    printf 'Missing installed headers for %s\n' "$abi" >&2
    return 70
  fi
}

for abi in "${ABIS[@]}"; do
  build_abi "$abi"
done

printf '%s\n' "$EXPECTED_FFMPEG_COMMIT" > "$STAGING_ROOT/SOURCE_COMMIT"
printf '%s\n' "$EXPECTED_FFMPEG_TAG" > "$STAGING_ROOT/SOURCE_TAG"
printf '%s\n' "${DECODERS[*]}" > "$STAGING_ROOT/ENABLED_DECODERS"
printf '%s\n' "$ANDROID_API" > "$STAGING_ROOT/ANDROID_API"
printf '%s\n' "$ACTUAL_NDK_REVISION" > "$STAGING_ROOT/NDK_VERSION"

mv "$STAGING_ROOT" "$BUILD_ROOT"
printf '\nFFmpeg four-ABI build completed: %s\n' "$BUILD_ROOT"
