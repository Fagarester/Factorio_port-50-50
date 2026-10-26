cmake_minimum_required(VERSION 3.22.1)

project(Gladio C)
message("Building ${PROJECT_NAME}")

set(CMAKE_VERBOSE_MAKEFILE on)
set(WARNS "-Wall -Wno-discarded-qualifiers -Wno-unused-function -Wimplicit-function-declaration -Werror=incompatible-pointer-types")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 ${WARNS} -DGL_GLEXT_PROTOTYPES")

include_directories(include)

add_library(GL SHARED
            src/main.c
            src/gl_calls.c
            src/glx_calls.c
            src/arrays.c
            src/ring_buffer.c
            src/gl_buffer.c
            src/gl_vao.c)

find_library(X11_LIB X11)
target_link_libraries(GL pthread dl m ${X11_LIB})
set_target_properties(GL PROPERTIES VERSION 1.7.0 SOVERSION 1)
