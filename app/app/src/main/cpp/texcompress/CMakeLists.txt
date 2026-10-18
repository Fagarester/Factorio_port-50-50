cmake_minimum_required(VERSION 3.22.1)

project(TexCompress C)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")

add_library(texcompress SHARED
            src/texcompress.c
            src/texcompress_jni.c)

# zlib is part of the NDK sysroot
target_link_libraries(texcompress z)
