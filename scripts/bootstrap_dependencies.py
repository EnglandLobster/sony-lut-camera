#!/usr/bin/env python3
"""Fetch pinned build dependencies; never overwrite existing or modified inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


def bootstrap(check_only=False, helper=False):
    lock = json.loads((ROOT / "dependencies.lock.json").read_text())
    for spec in lock["git"]:
        path = ROOT / spec["path"]
        if not path.exists():
            if check_only:
                raise RuntimeError("Missing dependency: " + spec["path"])
            path.parent.mkdir(parents=True, exist_ok=True)
            temp = Path(tempfile.mkdtemp(prefix="dependency-", dir=path.parent))
            try:
                git(temp, "init", "--quiet")
                git(temp, "remote", "add", "origin", spec["url"])
                git(temp, "fetch", "--depth=1", "origin", spec["commit"])
                git(temp, "checkout", "--detach", "--quiet", "FETCH_HEAD")
                temp.rename(path)
            finally:
                if temp.exists():
                    shutil.rmtree(temp)
        if git(path, "rev-parse", "HEAD") != spec["commit"] or git(path, "status", "--porcelain"):
            raise RuntimeError("Dependency differs from lock; preserve and inspect: " + spec["path"])
        print("Verified", spec["path"])
    for spec in lock["files"]:
        if spec["scope"] == "helper" and not helper:
            continue
        path = ROOT / spec["path"]
        if not path.exists():
            if check_only:
                raise RuntimeError("Missing dependency: " + spec["path"])
            path.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(spec["url"], timeout=60) as response:
                data = response.read(64 * 1024 * 1024 + 1)
            if hashlib.sha256(data).hexdigest() != spec["sha256"]:
                raise RuntimeError("Downloaded dependency checksum mismatch: " + spec["path"])
            # Exclusive creation avoids overwriting a concurrently supplied input.
            with path.open("xb") as stream:
                stream.write(data)
        if hashlib.sha256(path.read_bytes()).hexdigest() != spec["sha256"]:
            raise RuntimeError("Dependency checksum mismatch; preserve and inspect: " + spec["path"])
        print("Verified", spec["path"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify local dependencies without downloading")
    parser.add_argument("--helper", action="store_true", help="include the optional development connector APK dependency")
    args = parser.parse_args()
    bootstrap(args.check, args.helper)
