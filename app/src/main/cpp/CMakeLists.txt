cmake_minimum_required(VERSION 3.22.1)
project(tinyasr_jni LANGUAGES C CXX)

set(TINYASR_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")

add_library(tinyasr STATIC IMPORTED)
set_target_properties(tinyasr PROPERTIES
    IMPORTED_LOCATION "${TINYASR_LIB_DIR}/libtinyasr.a"
)

add_library(tinyasr_jni SHARED tinyasr_jni.cpp audio_onnx_jni.cpp gigaam_v4_jni.cpp)

target_include_directories(tinyasr_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/tinyasr/include"
    "${ONNX_HEADERS}"
)

target_link_libraries(tinyasr_jni
    tinyasr
    log
    android
    m
    dl
)
