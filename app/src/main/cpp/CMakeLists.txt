cmake_minimum_required(VERSION 3.22)
project(secretary C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Build whisper.cpp + ggml as static libraries linked into a single JNI .so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

# CPU only. OpenMP threads spin-wait between ops, which costs power and heat on a phone,
# so use ggml's own threadpool instead.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Exynos 2600 (Arm C1 cores, Armv9) supports fp16, dotprod and i8mm.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod+i8mm" CACHE STRING "" FORCE)

# Always optimise the inference code, even in debug APKs.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(secretary SHARED secretary_jni.cpp)
target_link_libraries(secretary PRIVATE whisper android log)
