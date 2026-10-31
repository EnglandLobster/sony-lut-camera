#!/usr/bin/env python3
"""Exercise PMCA's existing wired service mode on the connected A6300.

Temporarily claims USB, reads diagnostic state, echoes three terminal lines,
and round-trips one uniquely named /tmp file. No settings/firmware writes.
The upstream function exits service mode in its finally block.
"""
import argparse
import json
from pathlib import Path
import signal
import sys
import time
import uuid
import traceback

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "vendor/Sony-PMCA-RE"))
import usb.core
import usb.util
import usb.backend.libusb1
from pmca.commands.usb import senserShellCommand
from pmca.usb.driver.generic import libusb as pmca_libusb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--libusb-dll", type=Path,
                        help="Explicit libusb-1.0 library for a portable Windows runtime")
    parser.add_argument("--rebuild-interface", action="store_true",
                        help="Recreate USB configuration if macOS returns LIBUSB_ERROR_OTHER after capture")
    args = parser.parse_args()
    if args.libusb_dll:
        backend = usb.backend.libusb1.get_backend(find_library=lambda name: str(args.libusb_dll))
        if backend is None:
            raise RuntimeError("Unable to load the requested libusb library")
    result = {"started": time.time(), "transport": "USB service mode", "steps": []}

    def note(value):
        result["steps"].append(value)
        print(value, flush=True)

    def check(camera):
        result["service_connected"] = True
        note("Service authentication completed")
        # Optional existing diagnostic file: absence does not prevent testing
        # the independent built-in service shell.
        try:
            data = camera.readFile("/android/data/USBDBG/TEST.LOG")
            result["device_test_log"] = data.decode("utf-8", errors="replace")
            note("Previous helper log read over USB")
        except Exception as error:
            result["device_log_error"] = str(error)

        path = "/tmp/U" + uuid.uuid4().hex[:7].upper() + ".TST"
        payload = b"SONY_WIRED_DEBUG_ROUNDTRIP\n" + uuid.uuid4().hex.encode() + b"\n"
        created = False
        try:
            camera.writeFile(path, payload)
            created = True
            actual = camera.readFile(path)
            result["file_roundtrip"] = actual == payload
            result["file_bytes"] = len(actual)
            if actual != payload:
                raise RuntimeError("USB file roundtrip differs")
            note("USB file write/read matched exactly")
        finally:
            if created:
                camera.deleteFile(path)
                note("Temporary test file deleted")

        terminal = bytearray()
        try:
            camera.setTerminalEnable(False)
            camera.setTerminalEnable(True)
            camera.dev.writeTerminal(
                b"echo WIRED_USB_BEGIN\n"
                b"cat /proc/sys/kernel/random/boot_id\n"
                b"cat /proc/uptime\n"
                b"cat /proc/self/status\n"
                b"echo WIRED_STREAM_1; sleep 1; echo WIRED_STREAM_2; sleep 1; echo WIRED_STREAM_3\n"
                b"echo WIRED_USB_END\n")
            end = time.monotonic() + 7
            while time.monotonic() < end:
                terminal.extend(camera.dev.readTerminal())
            text = terminal.decode("utf-8", errors="replace")
            result["terminal"] = text
            # A command's local echo is not sufficient: require an output line.
            lines = [line.strip("\r\x00 ") for line in text.split("\n")]
            result["terminal_echo_verified"] = all(
                item in lines for item in ("WIRED_USB_BEGIN", "WIRED_STREAM_1",
                                           "WIRED_STREAM_2", "WIRED_STREAM_3", "WIRED_USB_END"))
            if not result["terminal_echo_verified"]:
                raise RuntimeError("Terminal output markers not verified")
            note("USB shell and timed output verified")
        finally:
            camera.setTerminalEnable(False)

    def deadline(signum, frame):
        raise TimeoutError("Service test exceeded 50 seconds")

    if args.rebuild_interface:
        original_backend = pmca_libusb.UsbBackend
        class RebuildingBackend(original_backend):
            def reset(self):
                if self._interfacePrepared:
                    return
                super().reset()
                try:
                    usb.util.claim_interface(self.dev, 0)
                except usb.core.USBError as error:
                    if error.backend_error_code != -99:
                        raise
                    note("macOS interface creation failed; rebuilding this USB configuration")
                    configuration = self.dev.get_active_configuration().bConfigurationValue
                    self.dev.set_configuration(0)
                    self.dev.set_configuration(configuration)
                    time.sleep(0.3)
                    usb.util.claim_interface(self.dev, 0)
                    note("Recreated USB interface claimed")
        pmca_libusb.UsbBackend = RebuildingBackend

    code = 1
    try:
        devices = list(usb.core.find(find_all=True, idVendor=0x054c))
        if len(devices) != 1 or devices[0].idProduct not in (0x0ab9, 0x02a9, 0x0336):
            raise RuntimeError("Expected the one connected A6300 or its service USB identity")
        if hasattr(signal, "SIGALRM"):
            signal.signal(signal.SIGALRM, deadline)
            signal.alarm(50)
        senserShellCommand("libusb", complete=check)
        if not result.get("service_connected"):
            raise RuntimeError("The service callback was never reached")
        code = 0
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
        result["traceback"] = traceback.format_exc()
        note(result["error"])
        print(result["traceback"], flush=True)
    finally:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)
        # libusb's macOS detach captures the whole device. Restore the ordinary
        # driver even if the first bulk request failed before service mode.
        try:
            devices = usb.core.find(find_all=True, idVendor=0x054c, idProduct=0x0ab9) if sys.platform == "darwin" else []
            for device in devices:
                if device.get_active_configuration()[(0, 0)].bInterfaceClass == 8:
                    device.attach_kernel_driver(0)
                    note("Normal macOS camera driver restored")
        except Exception as error:
            result["restore_error"] = str(error)
        result["finished"] = time.time()
        result["success"] = code == 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
