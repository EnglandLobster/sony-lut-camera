"""Offline regressions for late shutters, old logcat history and pending stores."""
from pathlib import Path
import unittest

from prejpeg_observation import ObservationWindow, filter_levels


def line(pid, elapsed, event, detail=''):
    return f'01-01 00:00:00.000 {pid} {pid} I SonyPreJpeg: {elapsed} {event} {detail}'


class ObservationTest(unittest.TestCase):
    def started(self):
        w = ObservationWindow(600000, 0, True)
        w.feed(line(1530, 689959, 'prepare', 'run=/SONYLUT/R54CCEF1'), 1)
        w.feed(line(1530, 692401, 'ready'), 3)
        return w

    def test_real_log_history_and_late_shutter(self):
        w = ObservationWindow(600000, 0, True)
        log = Path(__file__).resolve().parents[1] / 'results/prejpeg-resume-20261020/XFINE_FRESH_AFTER_LOG.txt'
        accepted = []
        for text in log.read_text().splitlines():
            item = w.feed(text, 1)
            if item:
                accepted.append(item)
        self.assertEqual(w.pid, 1530)
        self.assertTrue(w.run.endswith('R54CCEF1'))
        self.assertTrue(any(x['event'] == 'store_begin' for x in accepted))
        self.assertFalse(any(x['pid'] == 1387 for x in accepted))
        w = self.started()
        self.assertIsNone(w.end_reason(328))  # Actual prior wait was 326 seconds.
        w.feed(line(1530, 1018612, 'armed'), 329)
        self.assertIsNone(w.end_reason(360))
        self.assertEqual(w.end_reason(369), 'capture_deadline')

    def test_history_and_other_pid_cannot_finish_run(self):
        w = self.started()
        self.assertIsNone(w.feed(line(1387, 700000, 'done'), 10))
        self.assertIsNone(w.feed(line(1530, 599999, 'done'), 10))
        self.assertIsNone(w.end_reason(400))
        self.assertEqual(w.end_reason(603), 'user_shutter_deadline')

    def test_duplicate_events_do_not_extend_deadline(self):
        w = self.started()
        w.feed(line(1530, 1018612, 'armed'), 10)
        w.feed(line(1530, 1018641, 'armed'), 20)
        self.assertEqual(w.end_reason(50), 'capture_deadline')

    def test_store_snapshot_once_and_completion(self):
        w = self.started()
        w.feed(line(1530, 1019083, 'store_begin'), 10)
        self.assertFalse(w.needs_snapshot(17))
        self.assertTrue(w.needs_snapshot(18))
        self.assertFalse(w.needs_snapshot(19))
        w.feed(line(1530, 1043677, 'timeout'), 35)
        self.assertIsNone(w.end_reason(37))
        self.assertEqual(w.end_reason(38), 'probe_timeout')
        w = self.started()
        w.feed(line(1530, 1019083, 'store_begin'), 10)
        w.feed(line(1530, 1019583, 'store_accepted'), 11)
        self.assertFalse(w.needs_snapshot(20))
        w.feed(line(1530, 1022000, 'done'), 12)
        self.assertEqual(w.end_reason(15), 'probe_done')

    def test_initialization_also_bounded(self):
        w = ObservationWindow(600000, 0, False)
        self.assertEqual(w.end_reason(30), 'initialization_deadline')

    def test_filter_restore_preserves_effective_levels(self):
        self.assertEqual(filter_levels(
            'default debug level: error\nsubsystem filter 0x150: info\n',
            (0x150, 0x55)), {0x150: 'INFO', 0x55: 'ERROR'})
        with self.assertRaises(ValueError):
            filter_levels('connection closed', (0x150, 0x55))


if __name__ == '__main__':
    unittest.main()
