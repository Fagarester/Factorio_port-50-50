# Gladio: the game's OpenGL (client libGL in the glibc runtime) translated to Android's GLES driver in the app.
# common/ = the original Winlator helpers (ring buffers, ashmem), server/ = the GL side, gladio_server.c = the
# socket the client connects to (Termux:X11 build, no Java X server).
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration -Werror=incompatible-pointer-types -Wno-error=incompatible-pointer-types-discards-qualifiers -Wno-incompatible-pointer-types-discards-qualifiers")

add_library(gladio_common STATIC
            common/src/arrays.c
            common/src/ring_buffer.c
            common/src/sysvshared_memory.c
            common/src/hwbuffer.c)
target_include_directories(gladio_common PUBLIC common/include)
set_target_properties(gladio_common PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
target_link_libraries(gladio_common log android)

set(GLADIO_SERVER_SOURCES
            server/src/gladio_server.c
            server/src/gl_context.c
            server/src/gl_renderer.c
            server/src/request_handler.c
            server/src/shader_material.c
            server/src/shader_converter.c
            server/src/gl_formats.c
            server/src/gl_texture.c
            server/src/attrib_stack.c
            server/src/gl_query.c
            server/src/compressed_texture.c
            server/src/arb_program.c
            server/src/gl_buffer.c
            server/src/gl_vao.c
            server/src/gl_framebuffer.c)
add_library(gladioserver SHARED ${GLADIO_SERVER_SOURCES})
target_include_directories(gladioserver PRIVATE server/include)
target_link_libraries(gladioserver gladio_common log android nativewindow EGL GLESv2 GLESv3)

# The same server on ANGLE (GLES on Vulkan) instead of the phone's GLES driver: its EGL and GLES libraries are named
# libEGL_angle.so / libGLESv2_angle.so, which the app copies from the phone's /system and loads first (GladioServer).
add_library(gladioserver_angle SHARED ${GLADIO_SERVER_SOURCES})
target_include_directories(gladioserver_angle PRIVATE server/include)
target_link_libraries(gladioserver_angle gladio_common log android nativewindow EGL GLESv2 GLESv3)
find_program(PATCHELF patchelf REQUIRED)
add_custom_command(TARGET gladioserver_angle POST_BUILD
    COMMAND ${PATCHELF} --replace-needed libEGL.so libEGL_angle.so --replace-needed libGLESv2.so libGLESv2_angle.so
                        --remove-needed libGLESv3.so $<TARGET_FILE:gladioserver_angle>
    VERBATIM)
