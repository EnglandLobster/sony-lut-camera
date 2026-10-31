// Diagnostic only. No events are sent without --send and explicit user approval.
// Compare identical clicks delivered to a PID versus the macOS HID event stream.
#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#include <unistd.h>

static NSString *const targetBundle = @"com.parallels.desktop.console";
static NSString *const targetTitle = @"Windows 11";

static NSDictionary *targetWindow(pid_t pid) {
    NSArray *windows = CFBridgingRelease(CGWindowListCopyWindowInfo(
        kCGWindowListOptionOnScreenOnly | kCGWindowListExcludeDesktopElements, 0));
    NSDictionary *found = nil;
    for (NSDictionary *w in windows) {
        if ([w[(id)kCGWindowOwnerPID] intValue] == pid &&
            [w[(id)kCGWindowLayer] intValue] == 0 &&
            [w[(id)kCGWindowName] isEqualToString:targetTitle]) {
            if (found) return nil; // Refuse ambiguous windows.
            found = w;
        }
    }
    return found;
}

int main(int argc, char **argv) { @autoreleasepool {
    if (argc < 4) {
        fprintf(stderr, "Usage: pd-event-route-probe pid|hid X_FRACTION Y_FRACTION [--send] [--activate] [--move]\n");
        return 2;
    }
    NSString *route = @(argv[1]);
    char *endX, *endY;
    double x = strtod(argv[2], &endX), y = strtod(argv[3], &endY);
    if ((!([route isEqualToString:@"pid"] || [route isEqualToString:@"hid"])) ||
        *endX || *endY || !isfinite(x) || !isfinite(y) || x <= 0 || x >= 1 || y <= 0.06 || y >= 1) {
        fprintf(stderr, "Invalid route or point outside the guest content.\n"); return 2;
    }
    BOOL send = NO, activate = NO, move = NO;
    for (int i=4; i<argc; i++) {
        if (!strcmp(argv[i], "--send")) send=YES;
        else if (!strcmp(argv[i], "--activate")) activate=YES;
        else if (!strcmp(argv[i], "--move")) move=YES;
        else { fprintf(stderr, "Unknown argument.\n"); return 2; }
    }
    NSArray *apps = [NSRunningApplication runningApplicationsWithBundleIdentifier:targetBundle];
    if (apps.count != 1) { fprintf(stderr, "Expected one running Parallels app.\n"); return 3; }
    NSRunningApplication *app=apps.firstObject;
    NSDictionary *window=targetWindow(app.processIdentifier);
    CGRect bounds;
    if (!window || !CGRectMakeWithDictionaryRepresentation((__bridge CFDictionaryRef)window[(id)kCGWindowBounds], &bounds)) {
        fprintf(stderr, "Exact Windows 11 window unavailable.\n"); return 3;
    }
    CGPoint point=CGPointMake(bounds.origin.x+x*bounds.size.width, bounds.origin.y+y*bounds.size.height);
    printf("route=%s pid=%d window=%u point=(%.1f,%.1f) send=%d activate=%d postAccess=%d\n",
        route.UTF8String, app.processIdentifier, [window[(id)kCGWindowNumber] unsignedIntValue],
        point.x, point.y, send, activate, CGPreflightPostEventAccess());
    fflush(stdout);
    if (!send) return 0;
    if (!CGPreflightPostEventAccess()) { fprintf(stderr, "Event access unavailable; no permission changes attempted.\n"); return 4; }
    if (activate) {
        [app activateWithOptions:0];
        for (int i=0; i<50 && !app.active; i++) usleep(10000);
    }
    if (!app.active || NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier != app.processIdentifier) {
        fprintf(stderr, "Parallels is not foreground; refusing input.\n"); return 4;
    }
    // Refresh after activation and reject a moved/resized window.
    NSDictionary *again=targetWindow(app.processIdentifier);
    if (!again || ![again[(id)kCGWindowBounds] isEqual:window[(id)kCGWindowBounds]]) {
        fprintf(stderr, "Target window changed; refresh screenshot first.\n"); return 4;
    }
    CGEventSourceRef source=CGEventSourceCreate(kCGEventSourceStateCombinedSessionState);
    CGEventRef current=CGEventCreate(NULL);
    CGPoint previous=CGEventGetLocation(current);
    printf("move=%d cursor_before=(%.1f,%.1f) flags=0x%llx\n",move,previous.x,previous.y,(unsigned long long)CGEventGetFlags(current));
    CFRelease(current);
    if (move) {
        CGEventRef moved=CGEventCreateMouseEvent(source,kCGEventMouseMoved,point,kCGMouseButtonLeft);
        CGEventSetIntegerValueField(moved,kCGMouseEventDeltaX,(int64_t)(point.x-previous.x));
        CGEventSetIntegerValueField(moved,kCGMouseEventDeltaY,(int64_t)(point.y-previous.y));
        if ([route isEqualToString:@"pid"]) CGEventPostToPid(app.processIdentifier,moved);
        else CGEventPost(kCGHIDEventTap,moved);
        CFRelease(moved);
        usleep(100000);
    }
    CGEventRef down=CGEventCreateMouseEvent(source,kCGEventLeftMouseDown,point,kCGMouseButtonLeft);
    CGEventRef up=CGEventCreateMouseEvent(source,kCGEventLeftMouseUp,point,kCGMouseButtonLeft);
    if (!down || !up) {
        if (down) CFRelease(down); if (up) CFRelease(up); if (source) CFRelease(source);
        fprintf(stderr,"Unable to create click events.\n"); return 5;
    }
    CGEventSetIntegerValueField(down,kCGMouseEventClickState,1);
    CGEventSetIntegerValueField(up,kCGMouseEventClickState,1);
    if ([route isEqualToString:@"pid"]) CGEventPostToPid(app.processIdentifier,down);
    else CGEventPost(kCGHIDEventTap,down);
    usleep(100000);
    if ([route isEqualToString:@"pid"]) CGEventPostToPid(app.processIdentifier,up);
    else CGEventPost(kCGHIDEventTap,up);
    CFRelease(down); CFRelease(up); if (source) CFRelease(source);
    puts("One click delivered; verify result with Computer Use.");
    return 0;
} }
