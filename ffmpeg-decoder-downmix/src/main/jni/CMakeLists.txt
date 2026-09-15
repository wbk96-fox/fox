# FOX.TV patched Media3 FFmpeg decoder JNI bridge.
#
# The static FFmpeg inputs are produced by build_ffmpeg.sh from the pinned
# checkout documented in ../../../README.md. They are never downloaded or
# selected dynamically by CMake.

cmake_minimum_required(VERSION 3.22.1 FATAL_ERROR)
project(ffmpegJNI LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(FFMPEG_SOURCE_DIR "" CACHE PATH "Pinned FFmpeg source checkout")
set(FFMPEG_BUILD_DIR "" CACHE PATH "FOX.TV FFmpeg four-ABI build root")

if(NOT IS_DIRECTORY "${FFMPEG_SOURCE_DIR}")
    message(FATAL_ERROR "FFMPEG_SOURCE_DIR is missing or invalid: '${FFMPEG_SOURCE_DIR}'")
endif()
if(NOT IS_DIRECTORY "${FFMPEG_BUILD_DIR}")
    message(FATAL_ERROR "FFMPEG_BUILD_DIR is missing or invalid: '${FFMPEG_BUILD_DIR}'")
endif()

set(FFMPEG_INSTALL_DIR "${FFMPEG_BUILD_DIR}/${ANDROID_ABI}/install")
set(FFMPEG_INCLUDE_DIR "${FFMPEG_INSTALL_DIR}/include")
if(NOT EXISTS "${FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h")
    message(FATAL_ERROR "FFmpeg headers are missing for ${ANDROID_ABI}: ${FFMPEG_INCLUDE_DIR}")
endif()

foreach(FFMPEG_LIBRARY avcodec avutil swresample)
    set(FFMPEG_ARCHIVE "${FFMPEG_INSTALL_DIR}/lib/lib${FFMPEG_LIBRARY}.a")
    if(NOT EXISTS "${FFMPEG_ARCHIVE}")
        message(FATAL_ERROR "Missing FFmpeg archive for ${ANDROID_ABI}: ${FFMPEG_ARCHIVE}")
    endif()
    add_library(${FFMPEG_LIBRARY} STATIC IMPORTED)
    set_target_properties(
        ${FFMPEG_LIBRARY}
        PROPERTIES IMPORTED_LOCATION "${FFMPEG_ARCHIVE}"
    )
endforeach()

find_library(ANDROID_LIB android REQUIRED)
find_library(LOG_LIB log REQUIRED)
find_library(Z_LIB z REQUIRED)
find_library(M_LIB m REQUIRED)
find_library(DL_LIB dl REQUIRED)

add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
target_include_directories(ffmpegJNI PRIVATE "${FFMPEG_INCLUDE_DIR}")
target_compile_options(
    ffmpegJNI
    PRIVATE
        -fvisibility=hidden
        -fstack-protector-strong
        -Wall
        -Wextra
)
target_compile_definitions(ffmpegJNI PRIVATE _FORTIFY_SOURCE=2)

target_link_libraries(
    ffmpegJNI
    PRIVATE
        swresample
        avcodec
        avutil
        ${ANDROID_LIB}
        ${LOG_LIB}
        ${Z_LIB}
        ${M_LIB}
        ${DL_LIB}
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()

# Keep both options explicit. NDK r28 defaults to flexible-page-size output,
# while these flags make the four-ABI artifact gate deterministic.
target_link_options(
    ffmpegJNI
    PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-Wl,-z,relro"
        "-Wl,-z,now"
)
