# Native code of PiPlayer: TagLib (tags) and Chromaprint (fingerprints) behind one JNI library.
#
# libtag.so stays a separate shared library: we use TagLib under its LGPL-2.1 option. Chromaprint is MIT (with the
# BSD-3 KissFFT) once its FFmpeg-derived resampler is left out, so it is linked statically into libpiplayer_jni.so.
# Several shared libraries share C++ types, so Gradle passes ANDROID_STL=c++_shared.
cmake_minimum_required(VERSION 3.22...3.31)
project(piplayer_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party")
if(NOT EXISTS "${THIRD_PARTY}/taglib/3rdparty/utfcpp/source/utf8.h"
   OR NOT EXISTS "${THIRD_PARTY}/chromaprint/src/chromaprint.h")
  message(FATAL_ERROR "Submodules missing: run 'git submodule update --init --recursive'")
endif()

# TagLib reads compressed ID3v2 frames only with zlib; without it the frames would be skipped silently.
find_package(ZLIB REQUIRED)

# --- TagLib 2.3.2 -----------------------------------------------------------------------------------------------
set(BUILD_SHARED_LIBS ON)
set(BUILD_TESTING OFF)
set(BUILD_EXAMPLES OFF)
set(BUILD_BINDINGS OFF)
set(VISIBILITY_HIDDEN ON)
set(ENABLE_CCACHE OFF)
add_subdirectory("${THIRD_PARTY}/taglib" "${CMAKE_BINARY_DIR}/taglib")
set(BUILD_SHARED_LIBS OFF)

# --- Chromaprint 1.6.2, our own target ---------------------------------------------------------------------------
# Its own CMakeLists is not used: it clashes with TagLib's targets and cannot find the bundled KissFFT under the NDK.
set(CHROMAPRINT "${THIRD_PARTY}/chromaprint")
add_library(chromaprint STATIC
  ${CHROMAPRINT}/src/chromaprint.cpp
  ${CHROMAPRINT}/src/audio_processor.cpp
  ${CHROMAPRINT}/src/chroma.cpp
  ${CHROMAPRINT}/src/chroma_resampler.cpp
  ${CHROMAPRINT}/src/chroma_filter.cpp
  ${CHROMAPRINT}/src/spectrum.cpp
  ${CHROMAPRINT}/src/fft.cpp
  ${CHROMAPRINT}/src/fft_lib_kissfft.cpp
  ${CHROMAPRINT}/src/fingerprinter.cpp
  ${CHROMAPRINT}/src/image_builder.cpp
  ${CHROMAPRINT}/src/simhash.cpp
  ${CHROMAPRINT}/src/silence_remover.cpp
  ${CHROMAPRINT}/src/fingerprint_calculator.cpp
  ${CHROMAPRINT}/src/fingerprint_compressor.cpp
  ${CHROMAPRINT}/src/fingerprint_decompressor.cpp
  ${CHROMAPRINT}/src/fingerprinter_configuration.cpp
  ${CHROMAPRINT}/src/fingerprint_matcher.cpp
  ${CHROMAPRINT}/src/utils/base64.cpp
  ${CHROMAPRINT}/src/3rdparty/kissfft/kiss_fft.c
  ${CHROMAPRINT}/src/3rdparty/kissfft/kiss_fftr.c
)
target_include_directories(chromaprint
  PUBLIC "${CHROMAPRINT}/src"
  PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/chromaprint" "${CHROMAPRINT}/src/3rdparty/kissfft")
target_compile_definitions(chromaprint
  PUBLIC CHROMAPRINT_NODLL
  PRIVATE HAVE_CONFIG_H _USE_MATH_DEFINES __STDC_LIMIT_MACROS __STDC_CONSTANT_MACROS)
set_target_properties(chromaprint PROPERTIES POSITION_INDEPENDENT_CODE ON)

# --- JNI ----------------------------------------------------------------------------------------------------------
add_library(piplayer_jni SHARED
  jni_onload.cpp
  fd_stream.cpp
  taglib_jni.cpp
  chromaprint_jni.cpp
)
# TagLib's 'tag' target publishes no build-tree include directories; take its own list and the generated config.
target_include_directories(piplayer_jni SYSTEM PRIVATE
  "${THIRD_PARTY}/taglib/taglib"
  "$<TARGET_PROPERTY:tag,INCLUDE_DIRECTORIES>"
  "${CMAKE_BINARY_DIR}/taglib")
target_link_libraries(piplayer_jni PRIVATE tag chromaprint log)
# Only JNI_OnLoad is exported; natives are registered there.
target_link_options(piplayer_jni PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map" "-Wl,--no-undefined")
set_target_properties(piplayer_jni PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
