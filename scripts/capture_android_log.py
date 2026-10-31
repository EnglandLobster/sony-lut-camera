#!/usr/bin/env python3
"""Bounded full INFO-level Android capture, including vendor/JNI failures."""
import argparse
from pathlib import Path
import subprocess
import threading
import time
from collect_link_state import sanitize

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',default='192.168.5.218:5555')
parser.add_argument('--seconds',type=int,default=120)
parser.add_argument('--out',type=Path,required=True)
args=parser.parse_args()
if not 1<=args.seconds<=180:parser.error('seconds must be 1..180')
args.out.parent.mkdir(parents=True,exist_ok=True)
proc=subprocess.Popen(['adb','-s',args.serial,'logcat','-v','threadtime','*:I'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors='replace')
def read():
    with args.out.open('x') as out:
        count=0
        for line in proc.stdout:
            line=sanitize(line);out.write(line);out.flush();count+=len(line)
            if count>=8*1024*1024:break
reader=threading.Thread(target=read,daemon=True);reader.start()
print('Android INFO capture active',flush=True)
try:
    end=time.monotonic()+args.seconds
    while proc.poll() is None and reader.is_alive() and time.monotonic()<end:time.sleep(.2)
finally:
    proc.terminate()
    try:proc.wait(timeout=3)
    except subprocess.TimeoutExpired:proc.kill();proc.wait()
    reader.join(timeout=2)
print('Android capture closed',flush=True)
