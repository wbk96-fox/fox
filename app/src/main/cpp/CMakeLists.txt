# FOX.TV — native Dolby Vision Profile 7 -> 8.1 RPU bridge (libdovi).
#
# Produces libdovi_bridge.so, loaded by
# com.foxtv.app.core.player.DoviBridge via System.loadLibrary("dovi_bridge").
#
# The JNI translation unit (dovi_bridge.cpp) declares the libdovi C ABI itself,
# so no libdovi headers are required to compile. Headers are only used, when
# available, to keep the declarations honest at build time.
#
# Gradle passes these cache entries (see app/build.gradle.kts):
#   DOVI_ENABLE_LIBDOVI        explicit ON/OFF — real archive or intentional stub
#   DOVI_LIBDOVI_STATIC_LIB    explicit path to libdovi.a (optional override)
#   DOVI_LIBDOVI_INCLUDE_DIR   explicit include dir (optional override)
#   DOVI_LIBDOVI_PREBUILT_ROOT root holding android-<abi>/{lib,include} trees
#
# Stub mode is permitted only by explicit DOVI_ENABLE_LIBDOVI=OFF. An ON request
# without a usable archive fails configuration instead of silently degrading the
# requested real conversion path.

cmake_minimum_required(VERSION 3.22.1)
project(dovi_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(DOVI_ENABLE_LIBDOVI "" CACHE STRING "ON links libdovi; OFF explicitly permits the stub")
set_property(CACHE DOVI_ENABLE_LIBDOVI PROPERTY STRINGS ON OFF)
if(NOT DOVI_ENABLE_LIBDOVI STREQUAL "ON" AND
   NOT DOVI_ENABLE_LIBDOVI STREQUAL "OFF")
    message(FATAL_ERROR
        "DOVI_ENABLE_LIBDOVI must be explicitly set to ON or OFF; "
        "stub mode is permitted only by explicit OFF.")
endif()

set(DOVI_LIBDOVI_STATIC_LIB "" CACHE STRING "Absolute path to libdovi.a")
set(DOVI_LIBDOVI_INCLUDE_DIR "" CACHE STRING "Absolute path to libdovi include dir")
set(DOVI_LIBDOVI_PREBUILT_ROOT "" CACHE STRING "Root of android-<abi> libdovi prebuilts")

# ---------------------------------------------------------------------------
# Map the Android ABI onto the prebuilt directory layout shipped in DV7/libdovi.
# ---------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(DOVI_ABI_DIR "android-arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(DOVI_ABI_DIR "android-armeabi-v7a")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(DOVI_ABI_DIR "android-x86_64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(DOVI_ABI_DIR "android-x86")
else()
    set(DOVI_ABI_DIR "")
endif()

set(DOVI_RESOLVED_LIB "")
set(DOVI_RESOLVED_INCLUDE "")

if(DOVI_ENABLE_LIBDOVI STREQUAL "ON")
    # An explicit override is authoritative: a typo must not be masked by a
    # fallback to a different archive.
    if(DOVI_LIBDOVI_STATIC_LIB)
        if(NOT EXISTS "${DOVI_LIBDOVI_STATIC_LIB}")
            message(FATAL_ERROR
                "DOVI_LIBDOVI_STATIC_LIB does not exist: '${DOVI_LIBDOVI_STATIC_LIB}'")
        endif()
        set(DOVI_RESOLVED_LIB "${DOVI_LIBDOVI_STATIC_LIB}")
    elseif(DOVI_LIBDOVI_PREBUILT_ROOT AND DOVI_ABI_DIR)
        set(_candidate "${DOVI_LIBDOVI_PREBUILT_ROOT}/${DOVI_ABI_DIR}/lib/libdovi.a")
        if(EXISTS "${_candidate}")
            set(DOVI_RESOLVED_LIB "${_candidate}")
        endif()
    endif()

    if(DOVI_LIBDOVI_INCLUDE_DIR)
        if(NOT EXISTS "${DOVI_LIBDOVI_INCLUDE_DIR}")
            message(FATAL_ERROR
                "DOVI_LIBDOVI_INCLUDE_DIR does not exist: '${DOVI_LIBDOVI_INCLUDE_DIR}'")
        endif()
        set(DOVI_RESOLVED_INCLUDE "${DOVI_LIBDOVI_INCLUDE_DIR}")
    elseif(DOVI_LIBDOVI_PREBUILT_ROOT AND DOVI_ABI_DIR)
        set(_inc "${DOVI_LIBDOVI_PREBUILT_ROOT}/${DOVI_ABI_DIR}/include")
        if(EXISTS "${_inc}")
            set(DOVI_RESOLVED_INCLUDE "${_inc}")
        endif()
    endif()

    if(NOT DOVI_RESOLVED_LIB)
        message(FATAL_ERROR
            "DOVI_ENABLE_LIBDOVI=ON requires libdovi.a for ABI '${ANDROID_ABI}'. "
            "Searched DOVI_LIBDOVI_STATIC_LIB='${DOVI_LIBDOVI_STATIC_LIB}' and "
            "'${DOVI_LIBDOVI_PREBUILT_ROOT}/${DOVI_ABI_DIR}/lib/libdovi.a'.")
    endif()
endif()

add_library(dovi_bridge SHARED dovi_bridge.cpp)

target_compile_options(dovi_bridge PRIVATE -fvisibility=hidden -Wall)

# NDK r28 emits 16 KB-compatible output by default. Keep the linker contract
# explicit because this target also absorbs prebuilt Rust objects and all four
# FOX.TV ABIs are audited uniformly.
target_link_options(dovi_bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

find_library(ANDROID_LOG_LIB log)

if(DOVI_ENABLE_LIBDOVI STREQUAL "ON")
    message(STATUS "dovi_bridge[${ANDROID_ABI}]: linking ${DOVI_RESOLVED_LIB}")
    add_library(libdovi STATIC IMPORTED GLOBAL)
    set_target_properties(libdovi PROPERTIES IMPORTED_LOCATION "${DOVI_RESOLVED_LIB}")
    target_compile_definitions(dovi_bridge PRIVATE DOVI_REAL_LINKED=1)
    if(DOVI_RESOLVED_INCLUDE)
        target_include_directories(dovi_bridge PRIVATE "${DOVI_RESOLVED_INCLUDE}")
    endif()
    # libdovi is a Rust staticlib: it needs the C runtime math/dl symbols.
    target_link_libraries(dovi_bridge PRIVATE libdovi ${ANDROID_LOG_LIB} m dl)
    # Rust staticlibs export their whole symbol table; drop everything that the
    # JNI surface does not need so the .so stays close to the C++-only size.
    target_link_options(dovi_bridge PRIVATE
        "-Wl,--gc-sections"
        "-Wl,--exclude-libs,ALL")
else()
    message(STATUS "dovi_bridge[${ANDROID_ABI}]: explicit stub mode")
    target_compile_definitions(dovi_bridge PRIVATE DOVI_REAL_LINKED=0)
    target_link_libraries(dovi_bridge PRIVATE ${ANDROID_LOG_LIB})
endif()
