#!/usr/bin/env python3
"""Offline ARM execution of the copied A6300 client up to its IPC boundary.

Vendor code itself chooses/normalizes the option values. Only library plumbing
(vectors, string compare, parameter constructors, mutexes) is modeled. No camera
connection or device changes. Requires unicorn; use uv run --with unicorn.
"""
from pathlib import Path
import json
import struct
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm_const import *

ROOT = Path(__file__).resolve().parents[1]


def emulate(options):
    u=Uc(UC_ARCH_ARM,UC_MODE_ARM)
    blob=(ROOT/'results/hw-jpeg-20261019/libs/libcameraseq.so').read_bytes()
    u.mem_map(0,0x20000);u.mem_write(0,blob)
    # This ELF has file offsets != VAs in its writable PT_LOAD segment.
    phoff=struct.unpack_from('<I',blob,28)[0];phsize,phnum=struct.unpack_from('<HH',blob,42)
    for i in range(phnum):
        p=struct.unpack_from('<8I',blob,phoff+i*phsize)
        if p[0]==1:u.mem_write(p[2],blob[p[1]:p[1]+p[4]])
    u.mem_map(0x1000000,0x100000);u.mem_map(0x2000000,0x100000)
    heap=0x1000000
    def alloc(data):
        nonlocal heap
        at=heap;heap+=(len(data)+7)&~7;u.mem_write(at,data);return at
    def word(at):return struct.unpack('<I',u.mem_read(at,4))[0]
    def write(at,value):u.mem_write(at,struct.pack('<I',value&0xffffffff))
    def string(at):
        if not at:return None
        out=bytearray()
        while True:
            c=u.mem_read(at+len(out),1)
            if c==b'\0':break
            out+=c
        return out.decode()
    selfptr=alloc(bytes(32));opt=alloc(bytes(60))
    keys=alloc(b''.join(struct.pack('<I',alloc(k.encode()+b'\0')) for k,v in options))
    integers=alloc(b''.join(struct.pack('<i',v if isinstance(v,int) else 0) for k,v in options))
    strings=alloc(b''.join(struct.pack('<I',alloc(v.encode()+b'\0') if isinstance(v,str) else 0) for k,v in options))
    write(opt+4,keys);write(opt+8,len(options));write(opt+24,integers);write(opt+44,strings)
    vectors={};parameters={};result={}
    def ret(value=0):u.reg_write(UC_ARM_REG_R0,value&0xffffffff);u.reg_write(UC_ARM_REG_PC,u.reg_read(UC_ARM_REG_LR))
    def hook(uc,pc,size,data):
        a,b,c,d=[u.reg_read(r) for r in [UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_R3]]
        if pc==0x5c64:ret(0 if string(a)==string(b) else 1)
        elif pc in [0x5c70,0x5df0,0x5dfc,0x9940]:ret()
        elif pc==0x9b00:write(a,0);ret(a)
        elif pc==0x5cc4:vectors[a]=[];ret(a)
        elif pc==0x5cd0:vectors[a].append(word(b));ret(len(vectors[a])-1)
        elif pc==0x5cac:parameters[a]={'type':'int','id':b,'value':c if c<2**31 else c-2**32};ret(a)
        elif pc==0x5c94:parameters[a]={'type':'string','id':b,'value':string(c)};ret(a)
        elif pc==0xe9a4:
            result.update({'message_id':hex(b),'parameters':[parameters[p] for p in vectors[c]]});u.emu_stop()
    u.hook_add(UC_HOOK_CODE,hook)
    for reg,val in [(UC_ARM_REG_R0,selfptr),(UC_ARM_REG_R1,1),(UC_ARM_REG_R2,opt),(UC_ARM_REG_SP,0x20ff000),(UC_ARM_REG_LR,0x1fff0)]:u.reg_write(reg,val)
    u.emu_start(0x9ce4,0x1fff0,count=200000)
    if not result:raise RuntimeError('IPC boundary not reached')
    return result


if __name__=='__main__':
    default=[('SPLIT_EXPOSURE',0),('AUTO_RELEASE_LOCK_ENABLED',1)]
    configured=default+[('MEMORY_MAP_FILE','/android/system/etc/memmap/libILDCSC_MUSASHI8Gb24m_PictureEffectPlus_00_00.so'),('EXPOSURE_COUNT',1),('RECORD_COUNT',1)]
    result={'method':'offline instruction emulation with mocked library plumbing; IPC never sent',
            'defaults':emulate(default),'configured':emulate(configured)}
    path=ROOT/'results/init-protocol-20261019/CLIENT_OPTION_EMULATION.json';path.write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
