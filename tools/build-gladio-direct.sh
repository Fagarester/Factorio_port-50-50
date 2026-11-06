#!/bin/bash
# "Gladio: phone's driver directly" (test): the Gladio server as a glibc program in the game's runtime, on the phone's
# own GLES driver through libhybris, instead of inside the app. Output: app/src/main/assets/gladio-direct/.
#
# libhybris: the tree runtime/hybris/build-hybris.sh unpacked and configured in /work/hybris (the neighbour's
# taowen/libhybris arlinux fork, patches applied); its EGL and GLES parts, which that script leaves out, are built here.
# The fork has no EGL window-system module: direct/eglplatform_null.c is ours (no windows, the server draws offscreen).
set -euo pipefail
cd "$(dirname "$0")/.."
HB=/work/hybris/hybris
SRC=app/src/main/cpp/gladio
OUT=app/src/main/assets/gladio-direct
OBJ=/tmp/gladio-direct-build
[ -f $HB/common/.libs/libhybris-common.so.1 ] || { echo "no libhybris tree in /work/hybris (runtime/hybris/build-hybris.sh)"; exit 1; }
# ANGLE through libhybris (direct/hybris-linker-angle.patch): the Android linker (q.so) answers dlopen(NULL) and keeps
# ANGLE's own libc++abi to ANGLE; libhybris-common keeps a thread's bionic TLS until the other thread-exit callbacks ran
grep -q is_angle_library $HB/common/q/linker.cpp || patch -p1 -d $HB < $SRC/direct/hybris-linker-angle.patch
make -C $HB/common -j"$(nproc)" >/dev/null
make -C $HB/common/q -j"$(nproc)" >/dev/null
make -C $HB/egl -j"$(nproc)" >/dev/null
make -C $HB/glesv2 -j"$(nproc)" >/dev/null

# The server's Android parts: GL_SERVER/__ANDROID__ as in the app's build, NDK GLES headers (direct/include), Android
# functions from the phone's libraries (direct/shim.c), the JNI entry called from main.c. GCC is stricter than the
# NDK's clang about what the app's sources do (implicit declarations): those stay warnings, as there.
JDK=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include
CF="-O2 -fPIC -w -D_GNU_SOURCE -DGL_SERVER=1 -D__ANDROID__=1 -D__ANDROID_API__=26 -D_Nullable= -D_Nonnull=
    -Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types -Wno-error=int-conversion
    -include stdint.h -include sys/ioctl.h -include fcntl.h
    -I$SRC/direct/include -I$SRC/common/include -I$SRC/server/include -I$JDK -I$JDK/linux"
rm -rf $OBJ && mkdir -p $OBJ/lib
# libhybris' EGL and GLES under their own names: in the game's process (GLADIO_INPROCESS) a libEGL.so.1 or
# libGLESv2.so.2 of the runtime (Mesa) must not stand in for them, nor they for Mesa's
cp -L $HB/egl/.libs/libEGL.so.1 $OBJ/lib/libhybris-egl.so.1
cp -L $HB/glesv2/.libs/libGLESv2.so.2 $OBJ/lib/libhybris-glesv2.so.2
cp -L $HB/common/.libs/libhybris-common.so.1 $HB/properties/.libs/libandroid-properties.so.1 $HB/common/q/.libs/q.so $OBJ/lib/
patchelf --set-soname libhybris-egl.so.1 $OBJ/lib/libhybris-egl.so.1
patchelf --set-soname libhybris-glesv2.so.2 $OBJ/lib/libhybris-glesv2.so.2
# hidden symbols: in the game's process only the entry points are seen (direct/sync.c, the JNI start)
CF="$CF -fvisibility=hidden"
for f in $SRC/server/src/*.c $SRC/common/src/*.c $SRC/direct/shim.c $SRC/direct/main.c $SRC/direct/sync.c; do
    gcc $CF -c "$f" -o $OBJ/"$(basename "$f" .c)".o &
done
wait
ls $OBJ/*.o | wc -l | grep -qx "$(ls $SRC/server/src/*.c $SRC/common/src/*.c | wc -l | awk '{print $1 + 3}')" || { echo "compile failed"; exit 1; }
LIBS="-L$OBJ/lib -l:libhybris-egl.so.1 -l:libhybris-glesv2.so.2 -l:libhybris-common.so.1 -lpthread -lm"
SERVER_OBJ=$(ls $OBJ/*.o | grep -v -e /main.o -e /sync.o)
# a program beside the game (GLADIO_DIRECT_PROCESS=1, the v46 way) ...
gcc -o $OBJ/gladio-server $SERVER_OBJ $OBJ/main.o $LIBS
# ... and the library the game's libGL loads (stage 3); $ORIGIN first, before the game's library path
gcc -shared -o $OBJ/libgladio-direct.so -Wl,-soname,libgladio-direct.so -Wl,-z,defs -Wl,--disable-new-dtags -Wl,-rpath,'$ORIGIN' \
    $SERVER_OBJ $OBJ/sync.o $LIBS
gcc -O2 -fPIC -shared -I$HB/include -I$HB/egl $SRC/direct/eglplatform_null.c -o $OBJ/eglplatform_null.so

rm -rf $OUT && mkdir -p $OUT
cp $OBJ/gladio-server $OBJ/libgladio-direct.so $OBJ/eglplatform_null.so $OBJ/lib/* $OUT/
for f in $OUT/*; do strip --strip-unneeded "$f"; done
# no build paths in the search paths: each library finds the others beside it
for f in $OUT/lib*.so*; do patchelf --remove-rpath "$f"; [ "$(basename "$f")" = libgladio-direct.so ] || patchelf --force-rpath --set-rpath '$ORIGIN' "$f"; done
patchelf --remove-rpath $OUT/gladio-server
patchelf --force-rpath --set-rpath '$ORIGIN' $OUT/libgladio-direct.so
for f in $OUT/*; do readelf -d "$f" | grep -E "NEEDED|SONAME|RPATH|RUNPATH" | sed "s|^|$(basename "$f"): |"; done
ls -la $OUT
