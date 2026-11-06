#!/bin/sh
# Builds the benchmark's frame timer (runtime/benchmark, LD_PRELOADed into the game) natively on an arm64 glibc
# Linux and puts it into the app's assets.
set -e
cd "$(dirname "$0")/.."
gcc -O2 -Wall -shared -fPIC -fvisibility=default -o app/src/main/assets/benchmark/libfactorybench.so \
    runtime/benchmark/factorybench.c -ldl -lpthread
strip --strip-unneeded app/src/main/assets/benchmark/libfactorybench.so
echo "app/src/main/assets/benchmark/libfactorybench.so updated"
