/* Minimal POSIX ABI used by the P2711 companion. CC BY-NC-SA 4.0. */
#ifndef MH2P_WHEEL_PLATFORM_H
#define MH2P_WHEEL_PLATFORM_H
#include <stdint.h>
#include <stddef.h>
#ifdef MH2P_HOST_TEST
#include <arpa/inet.h>
#include <dlfcn.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
typedef struct sockaddr_in mh_addr;
static uint64_t mh_now_ns(void) {
    struct timespec t;
    return clock_gettime(CLOCK_MONOTONIC, &t) ? 0 : (uint64_t)t.tv_sec * 1000000000u + t.tv_nsec;
}
#else
/* QNX6.6 ARM EABI: BSD sockaddr_in has byte length/family, unlike Linux.
 * No Linux system headers or libc object files enter the QNX build. These are
 * declarations of the public POSIX/QNX calls; no opaque pthread/FILE layout
 * is accessed. ClockTime avoids assumptions about time_t/timespec layout.
 */
_Static_assert(sizeof(void *) == 4 && sizeof(long) == 4, "ARM32 ABI required");
typedef uint32_t pthread_t;
typedef uint32_t socklen_t;
typedef struct mh_FILE FILE;
typedef struct {
    uint8_t sin_len, sin_family;
    uint16_t sin_port;
    struct { uint32_t s_addr; } sin_addr;
    uint8_t padding[8];
} mh_addr;
struct pollfd { int fd; int16_t events, revents; };
#define AF_INET 2
#define SOCK_DGRAM 2
#define POLLIN 1
#define RTLD_NEXT ((void *)(intptr_t)-3)
extern void *dlsym(void *, const char *);
extern int ClockTime(int, int, const uint64_t *, uint64_t *);
extern int socket(int, int, int);
extern int bind(int, const void *, socklen_t);
extern int recvfrom(int, void *, size_t, int, void *, socklen_t *);
extern int sendto(int, const void *, size_t, int, const void *, socklen_t);
extern int poll(struct pollfd *, unsigned, int);
extern int close(int);
extern int getpid(void);
extern char *getenv(const char *);
extern int setenv(const char *, const char *, int);
extern int unsetenv(const char *);
extern int pthread_create(pthread_t *, const void *, void *(*)(void *), void *);
extern pthread_t pthread_self(void);
extern int pthread_detach(pthread_t);
extern void *memcpy(void *, const void *, size_t);
extern void *memset(void *, int, size_t);
extern int memcmp(const void *, const void *, size_t);
extern FILE *fopen(const char *, const char *);
extern int fclose(FILE *);
extern int fseek(FILE *, long, int);
extern long ftell(FILE *);
extern int fprintf(FILE *, const char *, ...);
static uint64_t mh_now_ns(void) {
    uint64_t now = 0;
    return ClockTime(2, 0, NULL, &now) ? 0 : now;
}
#endif
_Static_assert(sizeof(mh_addr) == 16, "socket address ABI");
_Static_assert(sizeof(struct pollfd) == 8, "pollfd ABI");
/* Freestanding conversion: do not link a compiler runtime built for Linux just
 * to divide a64-bit clock value on ARM32. Bounded64-iteration unsigned division.
 */
static uint64_t mh_millis(uint64_t ns) {
    uint64_t quotient=0;
    uint32_t remainder=0;
    for (unsigned bit=0;bit<64;++bit) {
        remainder=(remainder<<1)|(uint32_t)(ns>>63);
        ns<<=1; quotient<<=1;
        if (remainder>=1000000u) { remainder-=1000000u; quotient|=1; }
    }
    return quotient;
}
#endif
