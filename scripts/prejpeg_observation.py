"""Match one probe run and bound observation relative to its physical shutter."""
import re


EVENT = re.compile(
    r'^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+SonyPreJpeg:\s+'
    r'(\d+)\s+(\w+)(?:\s+(.*))?$'
)


class ObservationWindow:
    def __init__(self, cutoff_ms, started, capture, wait_seconds=600):
        self.cutoff_ms = cutoff_ms
        self.started = started
        self.capture = capture
        self.wait_seconds = wait_seconds
        self.pid = None
        self.run = None
        self.ready_at = None
        self.shutter_at = None
        self.store_at = None
        self.store_pending = False
        self.snapshot_taken = False
        self.finished_at = None
        self.finished_event = None

    def feed(self, line, now):
        match = EVENT.match(line.strip())
        if not match:
            return None
        pid, elapsed, event, detail = match.groups()
        pid, elapsed = int(pid), int(elapsed)
        if elapsed < self.cutoff_ms:
            return None
        if self.pid is None:
            if event != 'prepare':
                return None
            run = re.search(r'(?:^|\s)run=(\S+)', detail or '')
            if not run:
                return None
            self.pid, self.run = pid, run[1]
        if pid != self.pid:
            return None
        if event == 'ready' and self.ready_at is None:
            self.ready_at = now
        if event == 'armed' and self.shutter_at is None:
            self.shutter_at = now
        if event == 'store_begin':
            self.store_at, self.store_pending = now, True
        if event == 'store_accepted':
            self.store_pending = False
        if event in ('done', 'pair_payloads_verified', 'failed', 'closed', 'timeout'):
            if self.finished_at is None:
                self.finished_at, self.finished_event = now, event
        return {'pid': pid, 'elapsed_ms': elapsed, 'event': event,
                'detail': detail or ''}

    def needs_snapshot(self, now):
        if (self.store_pending and not self.snapshot_taken and
                self.store_at is not None and now - self.store_at >= 8):
            self.snapshot_taken = True
            return True
        return False

    def end_reason(self, now):
        if self.finished_at is not None and now - self.finished_at >= 3:
            return 'probe_' + self.finished_event
        if self.shutter_at is not None:
            return 'capture_deadline' if now - self.shutter_at >= 40 else None
        if self.ready_at is not None:
            return 'user_shutter_deadline' if now - self.ready_at >= self.wait_seconds else None
        return 'initialization_deadline' if now - self.started >= 30 else None


def filter_levels(text, subsystems):
    """Fail before changing filters when their current levels cannot be read."""
    default = re.search(r'default debug level:\s*(\w+)', text, re.I)
    if not default:
        raise ValueError('ulogio did not report its default filter level')
    levels = {key: default[1].upper() for key in subsystems}
    for subsystem, level in re.findall(
            r'subsystem filter (0x[0-9a-f]+):\s*(\w+)', text, re.I):
        key = int(subsystem, 16)
        if key in levels:
            levels[key] = level.upper()
    if any(not re.fullmatch('[A-Z]+', value) for value in levels.values()):
        raise ValueError('unrecognized ulogio filter response')
    return levels
