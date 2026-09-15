#!/usr/bin/env bash
# Rebuild the pinned Media3 IAMF native payloads and produce an audited AAR.
set -euo pipefail

readonly MEDIA_REPOSITORY="https://github.com/androidx/media.git"
readonly MEDIA_COMMIT="b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9"
readonly LIBIAMF_REPOSITORY="https://github.com/AOMediaCodec/libiamf.git"
readonly LIBIAMF_COMMIT="f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63"
readonly NDK_VERSION="28.2.13676358"
readonly CMAKE_VERSION="3.31.6"
readonly NINJA_VERSION="1.12.1"
readonly ANDROID_PLATFORM="android-21"
readonly SOURCE_EPOCH="1731028505"
readonly BASE_AAR_SHA256="087a237d730868144d16aceea40a87992699efbff511245e81cdc0c71dbe7c96"
readonly OUTPUT_AAR_SHA256="c6fd3e65f8e4f343ddd6edb1ca4fb6dfa37f669afd5af4ab760995e6e03083d1"
readonly -a ABIS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")
declare -Ar EXPECTED_NATIVE_SHA256=(
  ["arm64-v8a/libiamf.so"]="4c4ff5b34476f901c5c9ee125504734ed6d272cfb0d3818a628630ef0cd718af"
  ["arm64-v8a/libiamfJNI.so"]="bebe86e2b564b84e93f1e3fc334a021b3e103876af9a2519ebfc4eac94857bf3"
  ["armeabi-v7a/libiamf.so"]="7c1c79cdb68ec4ce46e72fe5ba33cf2b57c1b13c40c26274d3fc25fe96083fa3"
  ["armeabi-v7a/libiamfJNI.so"]="67ee9223320e3a6013f75aa3267a3105b0342f47c6d8e1ac7ddcdc3273e0af4e"
  ["x86/libiamf.so"]="b7c54a1f7c61bb7d0a73f5e1bace858a7450fa036cf53ef0471558185fe9d7e9"
  ["x86/libiamfJNI.so"]="4ac2e4efd4c79d9a9a9cf6eb8b7b86580fd038ab9d111d621c4cb6f163616f41"
  ["x86_64/libiamf.so"]="e74610893b780c756ba5b95942239efb03b180f509bbc88ce34909087e4a140d"
  ["x86_64/libiamfJNI.so"]="5f593155dc3df8b4162a7edbee7f6f2f4dc651a46b28b389aae3f04a8746f780"
)

usage() {
  echo "Usage: $0 <pinned-base.aar> [work-directory] [output.aar]" >&2
  echo "The base AAR must have SHA-256 ${BASE_AAR_SHA256}." >&2
}

sha256_of() {
  local checksum
  checksum="$(sha256sum -- "$1")"
  printf '%s\n' "${checksum%% *}"
}

require_sha256() {
  local path="$1"
  local expected="$2"
  local actual
  actual="$(sha256_of "${path}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "SHA-256 mismatch for ${path}: expected ${expected}, got ${actual}" >&2
    exit 1
  fi
}

if [[ $# -lt 1 || $# -gt 3 ]]; then
  usage
  exit 2
fi

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
readonly BASE_AAR="$(realpath -- "$1")"
readonly WORK_ROOT="$(realpath --canonicalize-missing -- "${2:-${PROJECT_ROOT}/build/iamf-rebuild/repro-v1.1.0}")"
readonly OUTPUT_AAR="$(realpath --canonicalize-missing -- "${3:-${PROJECT_ROOT}/build/iamf-rebuild/output/lib-decoder-iamf-release.aar}")"
readonly MEDIA_DIR="${WORK_ROOT}/media3"
readonly JNI_DIR="${MEDIA_DIR}/libraries/decoder_iamf/src/main/jni"
readonly LIBIAMF_DIR="${JNI_DIR}/libiamf"
readonly BUILD_ROOT="${WORK_ROOT}/build"
readonly STAGED_JNI="${WORK_ROOT}/staged/jni"
readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/patrrwoj89/Android/Sdk}}"
readonly NDK_ROOT="${SDK_ROOT}/ndk/${NDK_VERSION}"
readonly TOOLCHAIN="${NDK_ROOT}/build/cmake/android.toolchain.cmake"
readonly LLVM_BIN="${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin"
readonly LLVM_STRIP="${LLVM_BIN}/llvm-strip"
readonly LLVM_READELF="${LLVM_BIN}/llvm-readelf"

if [[ ! -f "${BASE_AAR}" ]]; then
  echo "Base AAR not found: ${BASE_AAR}" >&2
  exit 1
fi
require_sha256 "${BASE_AAR}" "${BASE_AAR_SHA256}"
if [[ -e "${WORK_ROOT}" ]]; then
  echo "Refusing to reuse or delete an existing work directory: ${WORK_ROOT}" >&2
  exit 1
fi
for command in git cmake ninja python3 sha256sum; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command not found: ${command}" >&2
    exit 1
  fi
done
for file in "${TOOLCHAIN}" "${LLVM_STRIP}" "${LLVM_READELF}"; do
  if [[ ! -f "${file}" ]]; then
    echo "Pinned NDK tool not found: ${file}" >&2
    exit 1
  fi
done

cmake_output="$(cmake --version)"
cmake_first_line="${cmake_output%%$'\n'*}"
actual_cmake_version="${cmake_first_line##* }"
actual_ninja_version="$(ninja --version)"
if [[ "${actual_cmake_version}" != "${CMAKE_VERSION}" ]]; then
  echo "CMake version mismatch: expected ${CMAKE_VERSION}, got ${actual_cmake_version}" >&2
  exit 1
fi
if [[ "${actual_ninja_version}" != "${NINJA_VERSION}" ]]; then
  echo "Ninja version mismatch: expected ${NINJA_VERSION}, got ${actual_ninja_version}" >&2
  exit 1
fi

mkdir -p -- "${WORK_ROOT}" "$(dirname -- "${OUTPUT_AAR}")"
export GIT_LFS_SKIP_SMUDGE=1
export GIT_TERMINAL_PROMPT=0

checkout_sparse() {
  local repository="$1"
  local commit="$2"
  local destination="$3"
  local sparse_path="$4"

  mkdir -p -- "${destination}"
  git -C "${destination}" init --quiet
  git -C "${destination}" remote add origin "${repository}"
  git -C "${destination}" sparse-checkout init --cone
  git -C "${destination}" sparse-checkout set "${sparse_path}"
  git -C "${destination}" fetch --quiet --depth=1 --filter=blob:none origin "${commit}"
  git -C "${destination}" checkout --quiet --detach FETCH_HEAD
  if [[ "$(git -C "${destination}" rev-parse HEAD)" != "${commit}" ]]; then
    echo "Source checkout mismatch in ${destination}" >&2
    exit 1
  fi
}

checkout_sparse "${MEDIA_REPOSITORY}" "${MEDIA_COMMIT}" "${MEDIA_DIR}" "libraries/decoder_iamf"
checkout_sparse "${LIBIAMF_REPOSITORY}" "${LIBIAMF_COMMIT}" "${LIBIAMF_DIR}" "code"

git -C "${MEDIA_DIR}" apply --check "${SCRIPT_DIR}/16kb-linker.patch"
git -C "${MEDIA_DIR}" apply "${SCRIPT_DIR}/16kb-linker.patch"

export SOURCE_DATE_EPOCH="${SOURCE_EPOCH}"
for abi in "${ABIS[@]}"; do
  abi_build="${BUILD_ROOT}/${abi}"
  abi_stage="${STAGED_JNI}/${abi}"
  prefix_map_flags="-ffile-prefix-map=${WORK_ROOT}=/usr/src/foxtv/iamf"
  mkdir -p -- "${abi_build}" "${abi_stage}"
  cmake \
    -S "${JNI_DIR}" \
    -B "${abi_build}" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
    -DANDROID_NDK="${NDK_ROOT}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="${prefix_map_flags}" \
    -DCMAKE_CXX_FLAGS="${prefix_map_flags}" \
    -DBUILD_SHARED_LIBS=ON
  cmake --build "${abi_build}" --target iamf iamfJNI --parallel 1
  "${LLVM_STRIP}" --strip-unneeded \
    -o "${abi_stage}/libiamf.so" \
    "${abi_build}/libiamf/code/libiamf.so"
  "${LLVM_STRIP}" --strip-unneeded \
    -o "${abi_stage}/libiamfJNI.so" \
    "${abi_build}/libiamfJNI.so"
  require_sha256 \
    "${abi_stage}/libiamf.so" \
    "${EXPECTED_NATIVE_SHA256[${abi}/libiamf.so]}"
  require_sha256 \
    "${abi_stage}/libiamfJNI.so" \
    "${EXPECTED_NATIVE_SHA256[${abi}/libiamfJNI.so]}"
done

python3 "${SCRIPT_DIR}/repack_aar.py" \
  --base "${BASE_AAR}" \
  --jni-dir "${STAGED_JNI}" \
  --output "${OUTPUT_AAR}"
require_sha256 "${OUTPUT_AAR}" "${OUTPUT_AAR_SHA256}"
python3 "${SCRIPT_DIR}/verify_aar.py" \
  --base "${BASE_AAR}" \
  --candidate "${OUTPUT_AAR}" \
  --readelf "${LLVM_READELF}"

echo "Audited AAR created at ${OUTPUT_AAR}"
echo "Production app/libs was not modified."
