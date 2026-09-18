cmake_minimum_required(VERSION 3.22)
project(amper_mtmd_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(
    AMPER_MTMD_VULKAN
    "Build AMPER native MTMD with the llama.cpp Vulkan backend"
    OFF
)
set(
    AMPER_SPIRV_HEADERS_DIR
    ""
    CACHE PATH
    "Host SPIRV-Headers CMake package directory used by the Vulkan shader generator"
)
set(
    AMPER_VULKAN_HEADERS_DIR
    ""
    CACHE PATH
    "Headers-only Vulkan include root used by the Android Vulkan cross-build"
)
if (AMPER_MTMD_VULKAN AND AMPER_SPIRV_HEADERS_DIR)
    set(SPIRV-Headers_DIR "${AMPER_SPIRV_HEADERS_DIR}" CACHE PATH "" FORCE)
endif()
if (AMPER_MTMD_VULKAN AND AMPER_VULKAN_HEADERS_DIR)
    set(Vulkan_INCLUDE_DIR "${AMPER_VULKAN_HEADERS_DIR}" CACHE PATH "" FORCE)
endif()

include(FetchContent)

# Pinned upstream identity. Phase148 intentionally does not follow llama.cpp master at build time.
set(AMPER_LLAMA_CPP_COMMIT "972d2313bc0bf0a45f634f77d95c9fb03aeab12c")
FetchContent_Declare(
    llama_cpp
    URL "https://github.com/ggml-org/llama.cpp/archive/${AMPER_LLAMA_CPP_COMMIT}.tar.gz"
)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ${AMPER_MTMD_VULKAN} CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(amper_mtmd SHARED amper_mtmd_jni.cpp)

target_include_directories(amper_mtmd PRIVATE
    "${llama_cpp_SOURCE_DIR}/include"
    "${llama_cpp_SOURCE_DIR}/ggml/include"
    "${llama_cpp_SOURCE_DIR}/tools/mtmd"
)

target_link_libraries(amper_mtmd PRIVATE
    llama
    mtmd
    ggml
    android
    log
)

if (AMPER_MTMD_VULKAN)
    target_compile_definitions(amper_mtmd PRIVATE AMPER_MTMD_VULKAN=1)
endif()

target_compile_options(amper_mtmd PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
)
