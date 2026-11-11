/* P2711 secondary-input companion, CC BY-NC-SA 4.0. See VERIFIED_API.md.
 * No stock class/binary is included. One bounded UDP worker accepts intents;
 * foreign router calls occur only within the router's own incoming callback.
 */
#include "wheel_platform.h"
#include "aa_wheel_state.h"
#include <stdatomic.h>

#define PORT 46335
#define CAPACITY 8u
#define EXPORT __attribute__((visibility("default")))
#define ROUTE "_ZN13MessageRouter12routeMessageEhRK10shared_ptrI8IoBufferE"
#define MAPPING "_ZN13MessageRouter12setupMappingEhh"
#define INIT "_ZN13MessageRouter4initEP14ChannelManager"
#define SHUTDOWN "_ZN13MessageRouter8shutdownEv"
#define CLOSE "_ZN13MessageRouter23handleChannelCloseNotifEhRK24ChannelCloseNotification"
#define LOCAL_CLOSE "_ZN13MessageRouter12closeChannelEh"
#define OUTGOING "_ZN13MessageRouter13queueOutgoingEhPvj"

typedef int (*route_fn)(void *, uint8_t, void *);
typedef void (*mapping_fn)(void *, uint8_t, uint8_t);
typedef int (*init_fn)(void *, void *);
typedef void (*shutdown_fn)(void *);
typedef int (*close_fn)(void *, uint8_t, void *);
typedef int (*local_close_fn)(void *, uint8_t);
typedef void (*outgoing_fn)(void *, uint8_t, void *, unsigned);
static _Atomic(route_fn) next_route;
static _Atomic(mapping_fn) next_mapping;
static _Atomic(init_fn) next_init;
static _Atomic(shutdown_fn) next_shutdown;
static _Atomic(close_fn) next_close;
static _Atomic(local_close_fn) next_local_close;
static _Atomic(outgoing_fn) next_outgoing;
static struct mh2p_wheel_state state;
static _Atomic(void *) router;
static _Atomic uint32_t enabled, running, core_lock, cookie, drain_lock;
static _Atomic int listener = -1;
_Static_assert(ATOMIC_INT_LOCK_FREE==2 && ATOMIC_POINTER_LOCK_FREE==2,
               "Callback state requires lock-free ARM32 atomics");

/* UDP worker never reads state directly or holds the callback lock. */
static _Atomic uint32_t snap_version, snap_epoch, snap_input = 255, snap_video = 255;
static _Atomic uint32_t snap_bound, snap_have_frame, snap_frame_at;
static _Atomic uint32_t active_client, lease_generation, lease_active, lease_at, submitted;
static _Atomic uint32_t submitted_client, submitted_epoch, submitted_generation;
static uint64_t last_native_sent;
struct intent { uint32_t client, epoch, generation, sequence, received, lifetime; int step; };
static struct intent ring[CAPACITY];
static _Atomic uint32_t head, tail;

static uint32_t get32(const uint8_t *p) {
    return (uint32_t)p[0]<<24 | (uint32_t)p[1]<<16 | (uint32_t)p[2]<<8 | p[3];
}
static void put32(uint8_t *p, uint32_t n) {
    p[0]=(uint8_t)(n>>24); p[1]=(uint8_t)(n>>16); p[2]=(uint8_t)(n>>8); p[3]=(uint8_t)n;
}
static int enter(void) { return !atomic_exchange_explicit(&core_lock,1,memory_order_acquire); }
static void leave(void) { atomic_store_explicit(&core_lock,0,memory_order_release); }
static void publish(void) {
    ++snap_version;
    snap_epoch=state.epoch; snap_input=state.input_channel; snap_video=state.video_channel;
    snap_bound=state.rotary_bound; snap_have_frame=state.have_frame;
    snap_frame_at=(uint32_t)state.last_frame_ms;
    __sync_synchronize();
    ++snap_version;
}
struct snapshot { uint32_t epoch, flags, age, input, video; };
static struct snapshot snapshot(uint32_t now) {
    struct snapshot s={0,0,0,255,255};
    for (unsigned tries=0; tries<3; ++tries) {
        uint32_t version=snap_version, bound, seen, at;
        if (version&1) continue;
        __sync_synchronize();
        s.epoch=snap_epoch; s.input=snap_input; s.video=snap_video;
        bound=snap_bound; seen=snap_have_frame; at=snap_frame_at;
        __sync_synchronize();
        if (version != snap_version) continue;
        s.age=now-at;
        s.flags=(bound?1u:0u) | (s.video!=255?2u:0u) | (s.input!=255?4u:0u)
            | (seen&&s.age<=2000?8u:0u) | (enabled?16u:0u);
        return s;
    }
    s.flags=0;
    return s;
}
static void resolve(void) {
    if (!next_route) next_route=(int(*)(void*,uint8_t,void*))dlsym(RTLD_NEXT,ROUTE);
    if (!next_mapping) next_mapping=(void(*)(void*,uint8_t,uint8_t))dlsym(RTLD_NEXT,MAPPING);
    if (!next_init) next_init=(int(*)(void*,void*))dlsym(RTLD_NEXT,INIT);
    if (!next_shutdown) next_shutdown=(void(*)(void*))dlsym(RTLD_NEXT,SHUTDOWN);
    if (!next_close) next_close=(int(*)(void*,uint8_t,void*))dlsym(RTLD_NEXT,CLOSE);
    if (!next_local_close) next_local_close=(int(*)(void*,uint8_t))dlsym(RTLD_NEXT,LOCAL_CLOSE);
    if (!next_outgoing) next_outgoing=(void(*)(void*,uint8_t,void*,unsigned))dlsym(RTLD_NEXT,OUTGOING);
}
static void log_state(struct snapshot s) {
    FILE *f=fopen("/tmp/mh2p_wheel_native.log","a");
    if (!f) return;
    if (!fseek(f,0,2) && ftell(f)>262144) { fclose(f); f=fopen("/tmp/mh2p_wheel_native.log","w"); }
    if (!f) return;
    fprintf(f,"wheel epoch=%u flags=%u input=%u video=%u submitted=%u\n",
            s.epoch,s.flags,s.input,s.video,submitted);
    fclose(f);
}

/* MZ01 protocol:40-byte commands,48-byte status. Only this worker writes the
 * ring; only native callbacks consume. All queues and retries have fixed bounds.
 */
static void *worker(void *unused) {
    uint32_t accepted=0, previous_epoch=0, last_log=0, logged_flags=~0u, logged_sent=0;
    (void)unused;
    pthread_detach(pthread_self());
    while (running) {
        struct pollfd p={listener,POLLIN,0};
        uint32_t now=(uint32_t)mh_millis(mh_now_ns());
        struct snapshot s=snapshot(now);
        if (s.epoch!=previous_epoch) { lease_active=0; lease_generation=0; accepted=0; previous_epoch=s.epoch; }
        if (s.flags!=logged_flags || (submitted!=logged_sent && now-last_log>1000)) {
            log_state(s); logged_flags=s.flags; logged_sent=submitted; last_log=now;
        }
        int ready=poll(&p,1,250);
        if (!running) break;
        if (ready<0) { enabled=0; break; }
        if (!ready || !(p.revents&POLLIN)) continue;
        uint8_t b[80], response[48];
        mh_addr peer;
        socklen_t peer_size=sizeof(peer);
        int n=(int)recvfrom(listener,b,sizeof b,0,(void*)&peer,&peer_size);
        if (n!=40 || peer_size!=sizeof(peer) || memcmp(b,"MZ01",4)
                || b[5] || b[6] || b[7]!=40) continue;
        uint32_t client=get32(b+12), epoch=get32(b+20), gen=get32(b+24), seq=get32(b+28);
        uint32_t age=get32(b+36), value=get32(b+32);
        now=(uint32_t)mh_millis(mh_now_ns()); s=snapshot(now);
        if (b[4]==1 && client) {
            if (client!=active_client && (!lease_active || now-lease_at>250)) {
                lease_active=0; lease_generation=0; accepted=0; active_client=client;
            }
        } else if (client==active_client && client && get32(b+16)==cookie && epoch==s.epoch) {
            if (b[4]==3 && gen>=lease_generation && value<=1) {
                if (gen!=lease_generation) accepted=0;
                lease_active=0; __sync_synchronize();
                lease_generation=gen; lease_at=now; __sync_synchronize(); lease_active=value;
            } else if (b[4]==4 && gen==lease_generation && gen && lease_active
                       && now-lease_at<=250 && (s.flags&31)==31 && seq>accepted
                       && age<1000 && (value==1 || value==UINT32_MAX)) {
                uint32_t next=tail;
                __sync_synchronize();
                if ((uint32_t)(next-head)<CAPACITY) {
                    struct intent *q=&ring[next%CAPACITY];
                    q->client=client; q->epoch=epoch; q->generation=gen;
                    q->sequence=seq; q->received=now; q->lifetime=1000-age;
                    q->step=value==1?1:-1;
                    __sync_synchronize(); tail=next+1; accepted=seq;
                }
            }
        }
        memset(response,0,sizeof response); memcpy(response,"MZ01",4);
        response[4]=128; response[7]=48;
        memcpy(response+8,b+8,4); put32(response+12,active_client);
        put32(response+16,cookie); put32(response+20,s.epoch);
        put32(response+24,lease_generation); put32(response+28,accepted);
        put32(response+32,s.flags); put32(response+36,s.age);
        response[40]=(uint8_t)s.input; response[41]=(uint8_t)s.video;
        put32(response+44,submitted_client==active_client && submitted_epoch==s.epoch
              && submitted_generation==lease_generation ? submitted : 0);
        sendto(listener,response,sizeof response,0,(void*)&peer,peer_size);
    }
    return NULL;
}

static void drain(void *self, uint64_t now_ns) {
    struct intent event;
    struct mh2p_wheel_state current;
    uint32_t now=(uint32_t)mh_millis(now_ns);
    if (!enabled || self!=router || !next_outgoing
            || atomic_exchange_explicit(&drain_lock,1,memory_order_acquire)) return;
    if (!enter()) goto done;
    current=state;
    __sync_synchronize();
    if (head==tail || (last_native_sent && now_ns-last_native_sent<150000000u)) { leave(); goto done; }
    event=ring[head%CAPACITY]; __sync_synchronize(); head=head+1;
    leave();
    if (!mh2p_wheel_ready(&current,mh_millis(now_ns)) || !lease_active
            || event.client!=active_client || event.epoch!=current.epoch
            || event.generation!=lease_generation || now-lease_at>250
            || now-event.received>=event.lifetime) goto done;
    uint8_t report[32];
    /* gal's native clock helper returns sec*1e9+nsec and stock reportRelative
     * forwards it unchanged. Match that nanosecond timestamp convention. */
    size_t length=mh2p_wheel_report(now_ns,event.step,report);
    if (length && enabled && snap_epoch==event.epoch && self==router
            && lease_active && lease_generation==event.generation && active_client==event.client) {
        next_outgoing(self,(uint8_t)current.input_channel,report,(unsigned)length);
        last_native_sent=now_ns;
        submitted_client=event.client; submitted_epoch=event.epoch;
        submitted_generation=event.generation;
        submitted=event.sequence;
    }
done:
    atomic_store_explicit(&drain_lock,0,memory_order_release);
}

/* The pinned IoBuffer layout is verified in InputSource::routeMessage. Host
 * fixtures use native-width pointers; the QNX build uses the actual ARM layout.
 */
static int message(void *reference, const uint8_t **data, size_t *length) {
    if (!reference) return 0;
#ifdef MH2P_HOST_TEST
    struct fixture_buffer { uint8_t *data; uint32_t capacity, begin, end; };
    struct fixture_buffer *b=((struct fixture_buffer **)reference)[1];
    if (!b || !b->data || b->end<b->begin || b->end-b->begin>16777216u) return 0;
    *data=b->data+b->begin; *length=b->end-b->begin;
#else
    uint32_t *b=(uint32_t *)(uintptr_t)((uint32_t *)reference)[1];
    if (!b || !b[0] || b[3]<b[2] || b[3]-b[2]>16777216u) return 0;
    *data=(const uint8_t *)(uintptr_t)b[0]+b[2]; *length=b[3]-b[2];
#endif
    return *length>=2;
}

EXPORT int _ZN13MessageRouter4initEP14ChannelManager(void *self,void *manager) {
    resolve(); int result=next_init?next_init(self,manager):0;
    if (enabled && result) {
        lease_active=0;
        if (enter()) {
            uint32_t epoch=state.epoch;
            if (epoch==UINT32_MAX) enabled=0;
            else { mh2p_wheel_init(&state,epoch+1); router=self; publish(); }
            leave();
        } else enabled=0;
    }
    return result;
}
EXPORT void _ZN13MessageRouter12setupMappingEhh(void *self,uint8_t service,uint8_t channel) {
    resolve(); if (next_mapping) next_mapping(self,service,channel);
    if (enabled && self==router && (service==14 || service==21)) {
        lease_active=0;
        if (enter()) { mh2p_wheel_mapping(&state,service,channel); publish(); leave(); }
        else enabled=0;
    }
}
EXPORT void _ZN13MessageRouter8shutdownEv(void *self) {
    resolve();
    if (self==router) {
        lease_active=0;
        if (enter()) { mh2p_wheel_shutdown(&state); router=NULL; publish(); leave(); }
        else enabled=0;
    }
    if (next_shutdown) next_shutdown(self);
}
static void closed(void *self,uint8_t channel) {
    if (enabled && self==router && (channel==snap_input || channel==snap_video)) {
        lease_active=0;
        if (enter()) { mh2p_wheel_close(&state,channel); publish(); leave(); }
        else enabled=0;
    }
}
EXPORT int _ZN13MessageRouter23handleChannelCloseNotifEhRK24ChannelCloseNotification(void *self,uint8_t ch,void *note) {
    resolve(); closed(self,ch); return next_close?next_close(self,ch,note):-4;
}
EXPORT int _ZN13MessageRouter12closeChannelEh(void *self,uint8_t ch) {
    resolve(); closed(self,ch); return next_local_close?next_local_close(self,ch):0;
}
EXPORT int _ZN13MessageRouter12routeMessageEhRK10shared_ptrI8IoBufferE(void *self,uint8_t ch,void *ref) {
    const uint8_t *data=NULL; size_t size=0;
    uint64_t now;
    int type=-1;
    resolve();
    if (!enabled) return next_route?next_route(self,ch,ref):-4;
    now=mh_now_ns();
    if (enabled && self==router && message(ref,&data,&size)) type=(data[0]<<8)|data[1];
    int locked=type>=0 && enter();
    if (!locked && ch==snap_input && type==0x8002) { enabled=0; lease_active=0; }
    if (locked) {
        if (ch==state.input_channel && type==0x8002) {
            uint8_t reply[16];
            size_t n=mh2p_wheel_bind(&state,ch,data+2,size-2,reply);
            lease_active=0; publish(); leave();
            if (n && next_outgoing) next_outgoing(self,ch,reply,(unsigned)n);
            return 0;
        }
        if (ch==state.input_channel && type==0x8004) { leave(); return 0; }
        if (now && (type==0 || type==1)) mh2p_wheel_frame(&state,ch,mh_millis(now));
        publish(); leave();
    }
    int result=next_route?next_route(self,ch,ref):-4;
    if (now) drain(self,now);
    return result;
}

__attribute__((constructor)) static void start(void) {
    /* Loaded only into gal.real. Its child processes keep the baseline preload,
     * avoiding new socket imports in the unchanged decoder/other executables. */
    const char *children=getenv("MH2P_WHEEL_CHILD_PRELOAD");
    if (children) { setenv("LD_PRELOAD",children,1); unsetenv("MH2P_WHEEL_CHILD_PRELOAD"); }
    resolve();
    if (!next_route || !next_mapping || !next_init || !next_shutdown || !next_outgoing
            || !next_close || !next_local_close || !mh_now_ns()) return;
#ifndef MH2P_HOST_TEST
    const void *relative=dlsym(RTLD_NEXT,"_ZN11InputSource14reportRelativeEyji");
    static const uint8_t prefix[]={0x36,0x49,0x2d,0xe9,0xf0,0x4f,0x79,0x44};
    static const uint8_t mapping[]={0x81,0x54,0x70,0x47};
    if (!relative || memcmp((const void *)((uintptr_t)relative&~1u),prefix,sizeof prefix)
            || memcmp((const void *)((uintptr_t)next_mapping&~1u),mapping,sizeof mapping)) return;
#endif
    mh_addr address;
    memset(&address,0,sizeof address);
#ifndef MH2P_HOST_TEST
    address.sin_len=sizeof address;
#endif
    address.sin_family=AF_INET;
    address.sin_addr.s_addr=0x0100007fu;
    address.sin_port=(uint16_t)((PORT<<8)|(PORT>>8));
    listener=socket(AF_INET,SOCK_DGRAM,0);
    if (listener<0) return;
    if (bind(listener,(void*)&address,sizeof address)) { close(listener); listener=-1; return; }
    cookie=((uint32_t)mh_now_ns() ^ ((uint32_t)getpid()<<16))|1u;
    mh2p_wheel_init(&state,1); publish(); enabled=1; running=1;
    pthread_t thread;
    if (pthread_create(&thread,NULL,worker,NULL)) { enabled=running=0; close(listener); listener=-1; }
}
__attribute__((destructor)) static void stop(void) {
    enabled=running=lease_active=0;
    if (listener>=0) { int fd=listener; listener=-1; close(fd); }
}
