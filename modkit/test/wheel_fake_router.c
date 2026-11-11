/* Host-only native API fixture. No OEM code/layout is executed. */
#include <stdint.h>
#include <string.h>
static unsigned forwarded, count, channel, length;
static uint8_t packet[64];
int _ZN13MessageRouter4initEP14ChannelManager(void *self,void *manager) { (void)self;(void)manager;return 1; }
void _ZN13MessageRouter12setupMappingEhh(void *self,uint8_t service,uint8_t ch) { (void)self;(void)service;(void)ch; }
void _ZN13MessageRouter8shutdownEv(void *self) { (void)self; }
int _ZN13MessageRouter23handleChannelCloseNotifEhRK24ChannelCloseNotification(void *self,uint8_t ch,void *note) { (void)self;(void)ch;(void)note;return 0; }
int _ZN13MessageRouter12closeChannelEh(void *self,uint8_t ch) { (void)self;(void)ch;return 1; }
int _ZN13MessageRouter12routeMessageEhRK10shared_ptrI8IoBufferE(void *self,uint8_t ch,void *ref) { (void)self;(void)ch;(void)ref;forwarded++;return 7; }
void _ZN13MessageRouter13queueOutgoingEhPvj(void *self,uint8_t ch,void *bytes,unsigned size) {
    (void)self;
    if (size>sizeof packet) return;
    memcpy(packet,bytes,size);channel=ch;length=size;count++;
}
unsigned fixture_count(void) { return count; }
unsigned fixture_forwarded(void) { return forwarded; }
unsigned fixture_channel(void) { return channel; }
unsigned fixture_length(void) { return length; }
const uint8_t *fixture_packet(void) { return packet; }
