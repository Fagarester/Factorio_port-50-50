#include <time.h>
#include <unistd.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <limits.h>
#include <sched.h>
#include <time.h>

#include "ring_buffer.h"
#include "time_utils.h"
#include "events.h"

#define STRUCT_OFFSETS() \
    struct Offsets { \
        uint32_t head; \
        uint32_t tail; \
        uint32_t status; \
        uint32_t waiters; /* was padding: same layout as before */ \
        void* buffer; \
    }

#ifdef __ANDROID__
#include <android/log.h>
#define debug_printf(...) __android_log_print(ANDROID_LOG_DEBUG, "System.out", __VA_ARGS__)
#else
#define debug_printf(...) fprintf(stderr, __VA_ARGS__)
#endif

/*
 * Waiting (Factory): a short spin, then a futex sleep until the other side moves head / tail. It used to be
 * 500 sched_yield + usleep(100) in a loop: up to 10000 wakeups a second per waiting thread, busy cores and heat
 * while the game is CPU bound. The futex word is head or tail itself; "waiters" (in the old padding) tells the
 * other side whether a wake is needed. Shared between processes: no FUTEX_PRIVATE_FLAG.
 */
#define RING_SPIN_ITER 64
#define RING_FUTEX_TIMEOUT_NS 2000000

static inline atomic_uint* ringWaiters(RingBuffer* ring) {
    return (atomic_uint*)((char*)ring->sharedData + 12);
}

static inline void futexWait(atomic_uint* word, uint32_t expected) {
    struct timespec timeout = {0, RING_FUTEX_TIMEOUT_NS};
    syscall(SYS_futex, (uint32_t*)word, FUTEX_WAIT, expected, &timeout, NULL, 0);
}

static inline void futexWakeIfWaiting(RingBuffer* ring, atomic_uint* word) {
    if (atomic_load_explicit(ringWaiters(ring), memory_order_seq_cst) > 0)
        syscall(SYS_futex, (uint32_t*)word, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

void RingBuffer_setHead(RingBuffer* ring, uint32_t head) {
    atomic_store_explicit(ring->head, head, memory_order_seq_cst);
    futexWakeIfWaiting(ring, ring->head);
}

uint32_t RingBuffer_getHead(RingBuffer* ring) {
    return atomic_load_explicit(ring->head, memory_order_acquire);
}

void RingBuffer_setTail(RingBuffer* ring, uint32_t tail) {
    atomic_store_explicit(ring->tail, tail, memory_order_seq_cst);
    futexWakeIfWaiting(ring, ring->tail);
}

uint32_t RingBuffer_getTail(RingBuffer* ring) {
    return atomic_load_explicit(ring->tail, memory_order_acquire);
}

void RingBuffer_setStatus(RingBuffer* ring, uint32_t status) {
    atomic_fetch_or_explicit(ring->status, status, memory_order_seq_cst);
}

void RingBuffer_unsetStatus(RingBuffer* ring, uint32_t status) {
    atomic_fetch_and_explicit(ring->status, ~status, memory_order_seq_cst);
}

bool RingBuffer_hasStatus(RingBuffer* ring, uint32_t status) {
    return (atomic_load_explicit(ring->status, memory_order_seq_cst) & status);
}

RingBuffer* RingBuffer_create(int shmFd, uint32_t bufferSize) {
    STRUCT_OFFSETS();

    int shmSize = RingBuffer_getSHMemSize(bufferSize);
    void* sharedData = mmap(NULL, shmSize, PROT_READ | PROT_WRITE, MAP_SHARED, shmFd, 0);
    if (sharedData == MAP_FAILED) return NULL;
    memset(sharedData, 0, shmSize);

    RingBuffer* ring = calloc(1, sizeof(RingBuffer));
    ring->sharedData = sharedData;
    ring->head = sharedData + offsetof(struct Offsets, head);
    ring->tail = sharedData + offsetof(struct Offsets, tail);
    ring->status = sharedData + offsetof(struct Offsets, status);
    ring->buffer = sharedData + offsetof(struct Offsets, buffer);
    ring->bufferSize = bufferSize;

    RingBuffer_setStatus(ring, RING_STATUS_IDLE);
    return ring;
}

uint32_t RingBuffer_size(RingBuffer* ring) {
    return RingBuffer_getTail(ring) - RingBuffer_getHead(ring);
}

uint32_t RingBuffer_freeSpace(RingBuffer* ring) {
    return ring->bufferSize - RingBuffer_size(ring);
}

bool RingBuffer_read(RingBuffer* ring, void* data, uint32_t size) {
    if (size > ring->bufferSize) {
        debug_printf("ring: buffer overflow on read (%d/%d)\n", size, ring->bufferSize);
        return false;
    }

    if (!RingBuffer_waitForRead(ring, size)) return false;
    uint32_t head = RingBuffer_getHead(ring);
    uint32_t offset = head & (ring->bufferSize - 1);

    if ((offset + size) <= ring->bufferSize) {
        memcpy(data, ring->buffer + offset, size);
    }
    else {
        uint32_t start = ring->bufferSize - offset;
        memcpy(data, ring->buffer + offset, start);
        memcpy(data + start, ring->buffer, size - start);
    }

    RingBuffer_setHead(ring, head + size);
    return true;
}

bool RingBuffer_write(RingBuffer* ring, const void* data, uint32_t size) {
    if (size > ring->bufferSize) {
        debug_printf("ring: buffer overflow on write (%d/%d)\n", size, ring->bufferSize);
        return false;
    }

    if (!RingBuffer_waitForWrite(ring, size)) return false;
    uint32_t tail = RingBuffer_getTail(ring);
    uint32_t offset = tail & (ring->bufferSize - 1);

    if ((offset + size) <= ring->bufferSize) {
        memcpy(ring->buffer + offset, data, size);
    }
    else {
        uint32_t start = ring->bufferSize - offset;
        memcpy(ring->buffer + offset, data, start);
        memcpy(ring->buffer, data + start, size - start);
    }

    RingBuffer_setTail(ring, tail + size);
    return true;
}

uint32_t RingBuffer_getSHMemSize(uint32_t bufferSize) {
    STRUCT_OFFSETS();

    return bufferSize + offsetof(struct Offsets, buffer);
}

void RingBuffer_free(RingBuffer* ring) {
    if (!ring) return;

    if (ring->sharedData) {
        RingBuffer_setStatus(ring, RING_STATUS_EXIT);
        // sleepers wake up and see EXIT
        syscall(SYS_futex, (uint32_t*)ring->head, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
        syscall(SYS_futex, (uint32_t*)ring->tail, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
        munmap(ring->sharedData, RingBuffer_getSHMemSize(ring->bufferSize));
        ring->sharedData = NULL;
    }

    free(ring);
}

__thread uint64_t ring_read_wait_us = 0, ring_write_wait_us = 0;

static uint64_t ringNowUs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000u + t.tv_nsec / 1000;
}

static bool waitForReadSlow(RingBuffer* ring, uint32_t size);
static bool waitForWriteSlow(RingBuffer* ring, uint32_t size);

// the time spent waiting is summed up for the frame time trace (only when the data / space is not there at once)
bool RingBuffer_waitForRead(RingBuffer* ring, uint32_t size) {
    if (RingBuffer_size(ring) >= size) return true;
    uint64_t start = ringNowUs();
    bool result = waitForReadSlow(ring, size);
    ring_read_wait_us += ringNowUs() - start;
    return result;
}

bool RingBuffer_waitForWrite(RingBuffer* ring, uint32_t size) {
    if (RingBuffer_freeSpace(ring) >= size) return true;
    uint64_t start = ringNowUs();
    bool result = waitForWriteSlow(ring, size);
    ring_write_wait_us += ringNowUs() - start;
    return result;
}

static bool waitForReadSlow(RingBuffer* ring, uint32_t size) {
    while (ring->syncWait) {
        if (RingBuffer_size(ring) >= size) return true;
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;
        ring->syncWait(ring->syncArg);
    }
    for (int i = 0; i < RING_SPIN_ITER; i++) {
        if (RingBuffer_size(ring) >= size) return true;
        sched_yield();
    }
    for (;;) {
        if (RingBuffer_size(ring) >= size) return true;
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;
        uint32_t tail = atomic_load_explicit(ring->tail, memory_order_seq_cst);
        atomic_fetch_add_explicit(ringWaiters(ring), 1, memory_order_seq_cst);
        if (tail - RingBuffer_getHead(ring) < size) futexWait(ring->tail, tail);
        atomic_fetch_sub_explicit(ringWaiters(ring), 1, memory_order_seq_cst);
    }
}

static bool waitForWriteSlow(RingBuffer* ring, uint32_t size) {
    while (ring->syncWait) {
        if (RingBuffer_freeSpace(ring) >= size) return true;
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;
        ring->syncWait(ring->syncArg);
    }
    for (int i = 0; i < RING_SPIN_ITER; i++) {
        if (RingBuffer_freeSpace(ring) >= size) return true;
        sched_yield();
    }
    for (;;) {
        if (RingBuffer_freeSpace(ring) >= size) return true;
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;
        uint32_t head = atomic_load_explicit(ring->head, memory_order_seq_cst);
        atomic_fetch_add_explicit(ringWaiters(ring), 1, memory_order_seq_cst);
        if (ring->bufferSize - (RingBuffer_getTail(ring) - head) < size) futexWait(ring->head, head);
        atomic_fetch_sub_explicit(ringWaiters(ring), 1, memory_order_seq_cst);
    }
}