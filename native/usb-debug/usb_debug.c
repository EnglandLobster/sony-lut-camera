/* Temporary userspace Sony USB command service. No firmware or settings writes.
 * A6300 2.01 ABI verified against libusbcmd.so and usb_extcmd.ko:
 * Create @0x2590, PackParamDriver @0x2782, onReceive @0x279c, onSend @0x283c.
 * Explicit DESTROY is required: this driver's close() only frees fd metadata.
 */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

struct registration {
    uint8_t code, reserved;
    uint16_t feature;
    uint32_t unknown1, unknown2;
};
struct transfer {
    void *data;
    uint32_t size, result, timeout;
    uint16_t flags, reserved;
};
_Static_assert(sizeof(struct registration) == 12, "Sony registration ABI");
_Static_assert(sizeof(struct transfer) == 20, "Sony ARM32 transfer ABI");
#define CREATE 0xc00ceb00u
#define DESTROY 0xc002eb01u
#define RECEIVE 0xc014eb02u
#define SEND 0xc014eb03u
static volatile sig_atomic_t stopping;
static unsigned char packet[65536];
static void stop(int sig) { (void)sig; stopping = 1; }

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc != 2 || (strcmp(argv[1], "--probe") && strcmp(argv[1], "--serve"))) {
        fprintf(stderr, "usage: usb-debug --probe|--serve\n"); return 2;
    }
    signal(SIGINT, stop); signal(SIGTERM, stop); signal(SIGHUP, SIG_IGN);
    int fd = open("/dev/usb/usb_extcmd", O_RDWR);
    if (fd < 0) { perror("open usb_extcmd"); return 1; }
    struct registration reg = {0x7a, 0, 0x23, 0, 0};
    if (ioctl(fd, CREATE, &reg) != 0) {
        perror("register feature 0x23"); close(fd); return 1;
    }
    if (!strcmp(argv[1], "--serve") && setsid() < 0) perror("setsid");
    printf("REGISTERED time=%ld pid=%ld code=0x7a feature=0x23\n", (long)time(NULL), (long)getpid());
    int result = 0;
    if (!strcmp(argv[1], "--serve")) while (!stopping) {
        struct transfer rx = {packet, sizeof(packet), 0, 1000, 0, 0};
        if (ioctl(fd, RECEIVE, &rx) != 0) {
            if (errno == ETIME || errno == ETIMEDOUT || errno == EINTR) continue;
            if (errno == EPIPE || errno == ECANCELED) {
                printf("USB RESET errno=%d\n", errno); usleep(100000); continue;
            }
            perror("receive"); result = 1; break;
        }
        if (rx.size < 8 || rx.size > sizeof(packet)) {
            fprintf(stderr, "invalid request size %u\n", rx.size); result = 1; break;
        }
        uint32_t sequence; memcpy(&sequence, packet, 4);
        printf("REQUEST seq=%u size=%u cmd=%.4s\n", sequence, rx.size, packet + 4);
        int32_t response = memcmp(packet + 4, "TEST", 4) == 0 ? 0 : -1;
        if (memcmp(packet + 4, "EXIT", 4) == 0) { response = 0; stopping = 1; }
        memcpy(packet + 4, &response, 4);
        struct transfer tx = {packet, 8, 0, 3000, 0, 0};
        if (ioctl(fd, SEND, &tx) != 0) { perror("send"); result = 1; break; }
        printf("RESPONSE seq=%u size=%u result=%d\n", sequence, tx.size, response);
    }
    uint16_t unused = 0;
    if (ioctl(fd, DESTROY, &unused) != 0) { perror("deregister"); result = 1; }
    else printf("DEREGISTERED time=%ld signal=%d\n", (long)time(NULL), (int)stopping);
    close(fd); return result;
}
