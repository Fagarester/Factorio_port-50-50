// Gladio server inside the game's process (stage 3): the client's libGL loads this library (libgladio-direct.so) and
// calls gladio_inprocess_start. GLX requests still come over the socket (a thread here answers them), GL requests over
// the same ring buffers as before, but with sync on the request handler of a client thread is no thread of its own:
// it is a coroutine on the client's thread. Where the client would wait for the server (a reply, ring space) it runs
// the handler; where the handler would wait for the client (the next request, pixels of a texture) it hands the
// thread back. No futex sleeps and wakeups, no second core in between: the GLES driver is called on the game's thread.
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sched.h>

#include "gladio_server.h"

#define EXPORT __attribute__((visibility("default")))
// the GLES driver's shader compiler runs on it too; only touched pages cost memory
#define STACK_SIZE (16u << 20)
#define GUARD_SIZE (64u << 10)

#if !defined(__aarch64__)
#error "coroutine switch written for arm64 only"
#endif

typedef struct Coroutine {
    void* sp;        // the coroutine's stack pointer while it is suspended
    void* callerSp;  // the client's while the coroutine runs
    char* stack;
    GLContext* context;
    void* (*handler)(void*);
    int threadId;
    bool attached, running, finished;
    struct Coroutine* next;
} Coroutine;

// switchStack(save, to): callee-saved registers onto this stack, its pointer into *save, continue on the stack "to"
void gladio_switch_stack(void** save, void* to);
void gladio_coroutine_entry(void);
__asm__(
    ".text\n"
    ".p2align 2\n"
    ".type gladio_switch_stack, %function\n"
    "gladio_switch_stack:\n"
    "    sub sp, sp, #160\n"
    "    stp x19, x20, [sp, #0]\n"
    "    stp x21, x22, [sp, #16]\n"
    "    stp x23, x24, [sp, #32]\n"
    "    stp x25, x26, [sp, #48]\n"
    "    stp x27, x28, [sp, #64]\n"
    "    stp x29, x30, [sp, #80]\n"
    "    stp d8, d9, [sp, #96]\n"
    "    stp d10, d11, [sp, #112]\n"
    "    stp d12, d13, [sp, #128]\n"
    "    stp d14, d15, [sp, #144]\n"
    "    mov x2, sp\n"
    "    str x2, [x0]\n"
    "    mov sp, x1\n"
    "    ldp x19, x20, [sp, #0]\n"
    "    ldp x21, x22, [sp, #16]\n"
    "    ldp x23, x24, [sp, #32]\n"
    "    ldp x25, x26, [sp, #48]\n"
    "    ldp x27, x28, [sp, #64]\n"
    "    ldp x29, x30, [sp, #80]\n"
    "    ldp d8, d9, [sp, #96]\n"
    "    ldp d10, d11, [sp, #112]\n"
    "    ldp d12, d13, [sp, #128]\n"
    "    ldp d14, d15, [sp, #144]\n"
    "    add sp, sp, #160\n"
    "    ret\n"
    ".size gladio_switch_stack, .-gladio_switch_stack\n"
    // first switch to a new coroutine "returns" here with the Coroutine in x19
    ".type gladio_coroutine_entry, %function\n"
    "gladio_coroutine_entry:\n"
    "    mov x0, x19\n"
    "    bl gladio_coroutine_main\n"
    "    brk #0\n"
    ".size gladio_coroutine_entry, .-gladio_coroutine_entry\n"
);

static pthread_mutex_t coroutinesLock = PTHREAD_MUTEX_INITIALIZER;
static Coroutine* coroutines = NULL;

__attribute__((used)) void gladio_coroutine_main(Coroutine* coroutine) {
    coroutine->handler(coroutine->context);
    coroutine->finished = true;
    void* unused;
    gladio_switch_stack(&unused, coroutine->callerSp); // never resumed again
    __builtin_trap();
}

/* the handler waits for the client: back to it (the ring wait checks again when the client runs us next) */
static void yieldToClient(void* arg) {
    Coroutine* coroutine = arg;
    if (!coroutine->running) {
        sched_yield(); // not on the client's thread: cannot happen with the hooks below, but never spin hard
        return;
    }
    gladio_switch_stack(&coroutine->sp, coroutine->callerSp);
}

static bool createCoroutine(GLContext* context, int threadId, void* (*handler)(void*)) {
    Coroutine* coroutine = calloc(1, sizeof(Coroutine));
    if (!coroutine) return false;
    coroutine->stack = mmap(NULL, STACK_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (coroutine->stack == MAP_FAILED) {
        free(coroutine);
        return false;
    }
    mprotect(coroutine->stack, GUARD_SIZE, PROT_NONE);
    // the frame gladio_switch_stack restores: x19 = the coroutine, x30 = the entry, the rest zero
    void** frame = (void**)(coroutine->stack + STACK_SIZE - 160);
    memset(frame, 0, 160);
    frame[0] = coroutine;
    frame[11] = (void*)gladio_coroutine_entry;
    coroutine->sp = frame;
    coroutine->context = context;
    coroutine->handler = handler;
    coroutine->threadId = threadId;

    context->sync = coroutine;
    context->serverRing->syncWait = yieldToClient;
    context->serverRing->syncArg = coroutine;
    context->clientRing->syncWait = yieldToClient;
    context->clientRing->syncArg = coroutine;

    pthread_mutex_lock(&coroutinesLock);
    coroutine->next = coroutines;
    coroutines = coroutine;
    pthread_mutex_unlock(&coroutinesLock);
    return true;
}

static void destroyCoroutine(GLContext* context) {
    Coroutine* coroutine = context->sync;
    context->sync = NULL;
    if (!coroutine) return;
    pthread_mutex_lock(&coroutinesLock);
    for (Coroutine** link = &coroutines; *link; link = &(*link)->next) {
        if (*link == coroutine) {
            *link = coroutine->next;
            break;
        }
    }
    pthread_mutex_unlock(&coroutinesLock);
    // a suspended one still has the handler's frames on its stack: never run again, its stack stays (rare: client gone)
    if (!coroutine->finished) {
        fprintf(stderr, "gladio-direct: GL context of thread %d destroyed while its handler was suspended\n", coroutine->threadId);
        return;
    }
    munmap(coroutine->stack, STACK_SIZE);
    free(coroutine);
}

/* Starts the server in this process, listening on socketPath; sync = handlers on the clients' threads. 0 = ok. */
EXPORT int gladio_inprocess_start(const char* socketPath, int sync) {
    if (sync) {
        gladio_sync_create = createCoroutine;
        gladio_sync_destroy = destroyCoroutine;
    }
    char error[256];
    if (!GladioServer_listen(socketPath, error, sizeof(error))) {
        fprintf(stderr, "gladio-direct: %s\n", error);
        return -1;
    }
    return 0;
}

/* The coroutine of the client thread threadId's new GL context (made before the ring fds were sent), NULL = none. */
EXPORT void* gladio_sync_attach(int threadId) {
    pthread_mutex_lock(&coroutinesLock);
    Coroutine* found = NULL;
    for (Coroutine* coroutine = coroutines; coroutine; coroutine = coroutine->next) {
        if (coroutine->threadId == threadId && !coroutine->attached) {
            found = coroutine;
            break; // the newest first
        }
    }
    if (found) found->attached = true;
    pthread_mutex_unlock(&coroutinesLock);
    return found;
}

/* Runs the handler until it waits for the client; always on the client thread that attached it. 0 = it has ended. */
EXPORT int gladio_sync_run(void* handle) {
    Coroutine* coroutine = handle;
    if (coroutine->finished) return 0;
    if (coroutine->running) return 1; // re-entered from inside the handler: cannot happen (the handler has its own ring code)
    coroutine->running = true;
    gladio_switch_stack(&coroutine->callerSp, coroutine->sp);
    coroutine->running = false;
    return !coroutine->finished;
}

/* Stage 3c: which request codes the client may hand over directly (table from start, count entries). */
EXPORT const unsigned char* gladio_direct_table(int* start, int* count) {
    return getDirectRequests(start, count);
}

/* A request handled at once on the client's stack, no ring; 0 = not now (handler not between requests): send it. */
EXPORT int gladio_direct_call(void* handle, short requestCode, void* data, int size) {
    Coroutine* coroutine = handle;
    if (coroutine->finished || coroutine->running) return 0;
    return handleRequestDirect(coroutine->context, requestCode, data, size);
}

/* Before the client destroys the GL context: the handler sees the exit flag and ends on this thread (EGL context released). */
EXPORT void gladio_sync_finish(void* handle) {
    Coroutine* coroutine = handle;
    coroutine->context->running = false;
    RingBuffer_setStatus(coroutine->context->serverRing, RING_STATUS_EXIT);
    RingBuffer_setStatus(coroutine->context->clientRing, RING_STATUS_EXIT);
    while (gladio_sync_run(coroutine));
}
