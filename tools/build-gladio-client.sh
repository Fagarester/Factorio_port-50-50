#!/bin/sh
# Builds the Gladio client (libGL.so.1 for the glibc runtime) natively on an arm64 glibc Linux
# (needs libx11-dev libx11-xcb-dev libxcb1-dev libxcb-shm0-dev) and puts it into the app's assets.
set -e
cd "$(dirname "$0")/.."
rm -rf /tmp/gladio-build && mkdir /tmp/gladio-build
cmake -S runtime/gladio -B /tmp/gladio-build -DCMAKE_BUILD_TYPE=Release
make -C /tmp/gladio-build -j8
cp /tmp/gladio-build/libGL.so.1.7.0 app/src/main/assets/gladio/libGL.so.1
echo "app/src/main/assets/gladio/libGL.so.1 updated"
