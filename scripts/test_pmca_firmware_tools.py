import struct
import unittest
from collect_pmca_firmware import embedded_dat


class ContainerTests(unittest.TestCase):
    def test_modern_pe_wrapper(self):
        payload = b'\x89UFU\r\n\x1a\n' + struct.pack('>I4s', 3, b'FDAT') + b'abc'
        payload += struct.pack('>I4s', 4, b'DEND') + b'1234'
        self.assertEqual(embedded_dat(b'MZ' + bytes(127) + payload + b'signature'), payload)

    def test_old_wrapper_needs_fwtool(self):
        self.assertIsNone(embedded_dat(b'MZ old compressed Sony wrapper'))

    def test_partial_package_rejected(self):
        with self.assertRaisesRegex(ValueError, 'truncated'):
            embedded_dat(b'\x89UFU\r\n\x1a\n' + struct.pack('>I4s', 100, b'FDAT') + b'abc')
        with self.assertRaisesRegex(ValueError, 'end chunk'):
            embedded_dat(b'\x89UFU\r\n\x1a\n' + struct.pack('>I4s', 3, b'FDAT') + b'abc')


if __name__ == '__main__':
    unittest.main()
