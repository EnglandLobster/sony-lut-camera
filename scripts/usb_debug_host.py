#!/usr/bin/env python3
"""Bounded USB transport probes; run with .venv-pmca/bin/python.

Uses PMCA's existing Sony protocol implementations. Does not install firmware,
change camera settings, detach kernel drivers, or stop macOS processes.
"""
import argparse
import json
from pathlib import Path
import sys
import time
import signal

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "vendor/Sony-PMCA-RE"))
import usb.core
import usb.util
from pmca.usb.driver.generic.libusb import UsbBackend
from pmca.usb.driver.generic import GenericUsbDriver, MtpDriver
from pmca.usb.sony import (SonyMtpExtCmdDevice, SonySenserAuthDevice,
                          SonySenserDevice, SonySenserCamera)
from pmca.platform.backend.usb import UsbPlatformBackend


def open_camera():
    devices = list(usb.core.find(find_all=True, idVendor=0x054c))
    if len(devices) != 1:
        raise RuntimeError("Expected exactly one Sony USB device")
    dev = devices[0]
    interface = dev.get_active_configuration()[(0, 0)]
    if interface.bInterfaceClass != 6:
        raise RuntimeError("Select MTP configuration after safely ejecting camera disks")
    usb.util.claim_interface(dev, 0)
    backend = UsbBackend(dev)
    # Explicit claim succeeded. Avoid libusb's Darwin detach path, which treats
    # our own user client as an active driver and returns Access denied.
    backend._interfacePrepared = True
    return dev, backend


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["test", "service-auth"])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    evidence = {"mode": args.mode, "started": time.time(), "steps": []}
    dev = camera = auth = senser = None
    status = 1
    def deadline(signum, frame):
        raise TimeoutError("USB probe exceeded 25 seconds")
    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(25)
    try:
        dev, backend = open_camera()
        evidence["product_id"] = hex(dev.idProduct)
        if args.mode == "test":
            camera = SonyMtpExtCmdDevice(MtpDriver(backend))
            evidence["steps"].append("MTP session opened")
            link = UsbPlatformBackend(camera)
            for _ in range(3):
                start = time.monotonic()
                result = link._req(b"TEST")
                evidence["steps"].append({"result": result, "seconds": time.monotonic() - start})
        else:
            # PMCA normally starts this on MSC. This explicit probe determines
            # whether the same vendor control/auth handshake works from MTP.
            auth = SonySenserAuthDevice(GenericUsbDriver(backend))
            auth.start()
            evidence["steps"].append("Service vendor request sent")
            auth.authenticate()
            evidence["steps"].append("Service authentication completed")
            usb.util.dispose_resources(dev)
            # The first authentication switches USB personality. Reopen the
            # service device and authenticate as PMCA's serviceshell does.
            dev = None
            end = time.monotonic() + 5
            while time.monotonic() < end:
                candidates = [d for d in usb.core.find(find_all=True, idVendor=0x054c)
                              if d.idProduct in (0x02a9, 0x0336)]
                if len(candidates) == 1:
                    dev = candidates[0]
                    break
                time.sleep(0.25)
            if dev is None:
                raise RuntimeError("No service USB personality appeared")
            evidence["service_product_id"] = hex(dev.idProduct)
            usb.util.claim_interface(dev, 0)
            backend = UsbBackend(dev)
            backend._interfacePrepared = True
            driver = GenericUsbDriver(backend)
            auth = SonySenserAuthDevice(driver)
            auth.start()
            auth.authenticate()
            senser = SonySenserCamera(SonySenserDevice(driver))
            evidence["hasp_bytes"] = len(senser.readHasp())
            data = senser.readFile("/android/data/USBDBG/TEST.LOG")
            evidence["device_test_log"] = data.decode("utf-8", errors="replace")
            senser.setTerminalEnable(False)
            senser.setTerminalEnable(True)
            senser.dev.writeTerminal(b"echo WIRED_USB_OK\n")
            terminal = bytearray()
            end = time.monotonic() + 4
            while time.monotonic() < end:
                terminal.extend(senser.dev.readTerminal())
            evidence["terminal"] = terminal.decode("utf-8", errors="replace")
        status = 0
    except Exception as error:
        evidence["error"] = f"{type(error).__name__}: {error}"
    finally:
        signal.alarm(0)
        if senser is not None:
            try:
                senser.setTerminalEnable(False)
            except Exception as error:
                evidence["terminal_close_error"] = str(error)
        if camera is not None:
            try:
                camera.closeSession()
            except Exception as error:
                evidence["close_error"] = str(error)
        if auth is not None:
            auth.stop()
        if dev is not None:
            usb.util.dispose_resources(dev)
        evidence["finished"] = time.time()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, indent=2) + "\n")
        print(json.dumps(evidence, indent=2), flush=True)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
