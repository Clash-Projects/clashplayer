// Isochronous USB streaming over usbfs on the fd from UsbDeviceConnection.
// Java's USB API has no isochronous transfers, so audio packets are submitted here as URBs.
#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

#define FB_URBS 4
#define FB_NONE 0
#define FB_EXPLICIT 1  // feedback endpoint: one value per packet
#define FB_IMPLICIT 2  // capture endpoint: its packet sizes are the device clock

typedef struct {
    int fd;
    int ep;
    int num_urbs;
    int packets_per_urb;
    int max_urb_bytes;
    struct usbdevfs_urb **urbs;
    unsigned char **bufs;
    int *busy;
    int in_flight;
    // feedback / implicit-feedback capture
    int fb_mode;
    int fb_ep;
    int fb_packet;
    int fb_packets;
    int fb_frame_bytes;
    int fb_pending;
    struct usbdevfs_urb *fb_urbs[FB_URBS];
    unsigned char *fb_bufs[FB_URBS];
    atomic_long feedback; // explicit: raw value; implicit: frames per packet, Q16; 0 = none yet
} stream_t;

static struct usbdevfs_urb *alloc_iso_urb(int packets) {
    return calloc(1, sizeof(struct usbdevfs_urb) + packets * sizeof(struct usbdevfs_iso_packet_desc));
}

static int submit_fb(stream_t *s, int i) {
    struct usbdevfs_urb *u = s->fb_urbs[i];
    memset(u, 0, sizeof(*u) + s->fb_packets * sizeof(struct usbdevfs_iso_packet_desc));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) s->fb_ep;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = s->fb_bufs[i];
    u->buffer_length = s->fb_packet * s->fb_packets;
    u->number_of_packets = s->fb_packets;
    for (int p = 0; p < s->fb_packets; p++) u->iso_frame_desc[p].length = s->fb_packet;
    u->usercontext = (void *) (intptr_t) (-1 - i);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    s->fb_pending++;
    return 0;
}

static void handle_fb(stream_t *s, int i, struct usbdevfs_urb *u) {
    s->fb_pending--;
    if (u->status == 0) {
        if (s->fb_mode == FB_EXPLICIT) {
            int n = u->iso_frame_desc[0].actual_length;
            if (n >= 3) {
                unsigned char *b = s->fb_bufs[i];
                uint32_t v = (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | (n >= 4 ? ((uint32_t) b[3] << 24) : 0);
                atomic_store(&s->feedback, (long) v);
            }
        } else {
            long bytes = 0;
            for (int p = 0; p < u->number_of_packets; p++) bytes += u->iso_frame_desc[p].actual_length;
            if (bytes > 0) atomic_store(&s->feedback, (bytes << 16) / ((long) s->fb_frame_bytes * u->number_of_packets));
        }
    }
    if (s->fb_mode != FB_NONE) submit_fb(s, i);
}

// Blocks until one URB completes; data URBs are marked free, feedback URBs are decoded and resubmitted.
static int reap_one(stream_t *s) {
    struct usbdevfs_urb *u = NULL;
    while (ioctl(s->fd, USBDEVFS_REAPURB, &u) < 0) {
        if (errno != EINTR) return -errno;
    }
    intptr_t ctx = (intptr_t) u->usercontext;
    if (ctx < 0) {
        handle_fb(s, (int) (-1 - ctx), u);
    } else {
        s->busy[ctx] = 0;
        s->in_flight--;
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_clashplayer_usb_UsbIso_nativeOpen(JNIEnv *env, jclass clazz, jint fd, jint ep, jint max_packet,
                                           jint packets_per_urb, jint num_urbs, jint fb_mode, jint fb_ep,
                                           jint fb_packet, jint fb_packets, jint fb_frame_bytes) {
    stream_t *s = calloc(1, sizeof(stream_t));
    if (!s) return 0;
    s->fd = fd;
    s->ep = ep;
    s->num_urbs = num_urbs;
    s->packets_per_urb = packets_per_urb;
    s->max_urb_bytes = max_packet * packets_per_urb;
    s->urbs = calloc(num_urbs, sizeof(*s->urbs));
    s->bufs = calloc(num_urbs, sizeof(*s->bufs));
    s->busy = calloc(num_urbs, sizeof(int));
    for (int i = 0; i < num_urbs; i++) {
        s->urbs[i] = alloc_iso_urb(packets_per_urb);
        s->bufs[i] = malloc(s->max_urb_bytes);
    }
    atomic_init(&s->feedback, 0);
    if (fb_mode != FB_NONE && fb_ep > 0 && fb_packet > 0) {
        s->fb_mode = fb_mode;
        s->fb_ep = fb_ep;
        s->fb_packet = fb_packet;
        s->fb_packets = fb_packets > 0 ? fb_packets : 1;
        s->fb_frame_bytes = fb_frame_bytes > 0 ? fb_frame_bytes : 1;
        for (int i = 0; i < FB_URBS; i++) {
            s->fb_urbs[i] = alloc_iso_urb(s->fb_packets);
            s->fb_bufs[i] = calloc(1, (size_t) fb_packet * s->fb_packets);
            submit_fb(s, i);
        }
    }
    return (jlong) (intptr_t) s;
}

// Copies one URB worth of packed PCM and submits it; blocks while every URB is in flight.
JNIEXPORT jint JNICALL
Java_com_clashplayer_usb_UsbIso_nativeWrite(JNIEnv *env, jclass clazz, jlong handle, jbyteArray data,
                                            jint length, jintArray packet_bytes, jint packets) {
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (!s || packets > s->packets_per_urb || length > s->max_urb_bytes) return -EINVAL;
    int slot = -1;
    while (slot < 0) {
        for (int i = 0; i < s->num_urbs; i++) if (!s->busy[i]) { slot = i; break; }
        if (slot < 0) {
            int rc = reap_one(s);
            if (rc < 0) return rc;
        }
    }
    struct usbdevfs_urb *u = s->urbs[slot];
    memset(u, 0, sizeof(*u) + packets * sizeof(struct usbdevfs_iso_packet_desc));
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *) s->bufs[slot]);
    jint *lens = (*env)->GetIntArrayElements(env, packet_bytes, NULL);
    for (int p = 0; p < packets; p++) u->iso_frame_desc[p].length = (unsigned) lens[p];
    (*env)->ReleaseIntArrayElements(env, packet_bytes, lens, JNI_ABORT);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) s->ep;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = s->bufs[slot];
    u->buffer_length = length;
    u->number_of_packets = packets;
    u->usercontext = (void *) (intptr_t) slot;
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    s->busy[slot] = 1;
    s->in_flight++;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_clashplayer_usb_UsbIso_nativeFeedback(JNIEnv *env, jclass clazz, jlong handle) {
    stream_t *s = (stream_t *) (intptr_t) handle;
    return s ? atomic_load(&s->feedback) : 0;
}

// Cancels queued audio (seek); the feedback stream keeps running.
JNIEXPORT jint JNICALL
Java_com_clashplayer_usb_UsbIso_nativeFlush(JNIEnv *env, jclass clazz, jlong handle) {
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (!s) return -EINVAL;
    for (int i = 0; i < s->num_urbs; i++) if (s->busy[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    while (s->in_flight > 0) {
        int rc = reap_one(s);
        if (rc < 0) return rc;
    }
    return 0;
}

// Cancels everything, waits for the kernel to hand every URB back, then frees.
JNIEXPORT void JNICALL
Java_com_clashplayer_usb_UsbIso_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    stream_t *s = (stream_t *) (intptr_t) handle;
    if (!s) return;
    s->fb_mode = FB_NONE; // stop resubmitting feedback
    for (int i = 0; i < s->num_urbs; i++) if (s->busy[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    for (int i = 0; i < FB_URBS; i++) if (s->fb_urbs[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->fb_urbs[i]);
    // Discarded URBs still come back through REAPURB and must be reaped before their memory is freed
    while (s->in_flight > 0 || s->fb_pending > 0) {
        if (reap_one(s) < 0) break;
    }
    for (int i = 0; i < s->num_urbs; i++) { free(s->urbs[i]); free(s->bufs[i]); }
    for (int i = 0; i < FB_URBS; i++) { free(s->fb_urbs[i]); free(s->fb_bufs[i]); }
    free(s->urbs); free(s->bufs); free(s->busy); free(s);
}

// USB_SPEED_* from linux/usb/ch9.h, or -errno where the ioctl is unavailable.
JNIEXPORT jint JNICALL
Java_com_clashplayer_usb_UsbIso_nativeSpeed(JNIEnv *env, jclass clazz, jint fd) {
#ifdef USBDEVFS_GET_SPEED
    int r = ioctl(fd, USBDEVFS_GET_SPEED);
    return r < 0 ? -errno : r;
#else
    return -ENOSYS;
#endif
}

// Re-binds the kernel driver (snd-usb-audio) to an interface this app released, so Android can use the DAC again.
JNIEXPORT jint JNICALL
Java_com_clashplayer_usb_UsbIso_nativeReconnect(JNIEnv *env, jclass clazz, jint fd, jint interface_number) {
    struct usbdevfs_ioctl cmd = { .ifno = interface_number, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
    return ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0 ? -errno : 0;
}

// Detaches the kernel driver from an interface without claiming it (needed before switching configurations).
JNIEXPORT jint JNICALL
Java_com_clashplayer_usb_UsbIso_nativeDisconnect(JNIEnv *env, jclass clazz, jint fd, jint interface_number) {
    struct usbdevfs_ioctl cmd = { .ifno = interface_number, .ioctl_code = USBDEVFS_DISCONNECT, .data = NULL };
    return ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0 ? -errno : 0;
}
