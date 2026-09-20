cmake_minimum_required(VERSION 3.22)
project(amper_amne_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "AMNE Phase603 supports arm64-v8a only")
endif()

add_library(amper_amne SHARED amper_amne_jni.cpp)

target_link_libraries(amper_amne PRIVATE log)

target_compile_options(amper_amne PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -march=armv8-a+simd
)
