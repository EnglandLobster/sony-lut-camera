#!/usr/bin/env python3
"""Execute Sony's legacy getProperty ARM code against synthetic backing values.

Input is a locally extracted libcameraseq.so, never redistributed. Only the
property backend, logging and libc formatting are stubbed; key lookup, shared
buffer handling and control flow execute the original instructions. No device
connection, physical memory access or camera IPC. Requires unicorn/pyelftools.
"""
import argparse
import json
import struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC


def run(path):
    u = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    u.mem_map(0, 0x40000)
    with open(path, 'rb') as stream:
        elf = ELFFile(stream)
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                u.mem_write(segment['p_vaddr'], segment.data())
        dyn = elf.get_section_by_name('.dynsym')
        symbols = {s.name: s['st_value'] for s in dyn.iter_symbols()}
        plt = elf.get_section_by_name('.plt')['sh_addr']
        imports = {plt + 20 + 12 * i: dyn.get_symbol(rel['r_info_sym']).name
                   for i, rel in enumerate(elf.get_section_by_name('.rel.plt').iter_relocations())}
    u.mem_map(0x1000000, 0x100000)
    query = symbols['_ZN6scalar18DeviceMemoryDiadem11getPropertyEPKc']
    backend = symbols['_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi']
    ticket = symbols['_ZN6scalar18DeviceMemoryDiadem9getTicketEv']
    keys = []

    def string(at):
        result = bytearray()
        while True:
            b = bytes(u.mem_read(at + len(result), 1))
            if b == b'\0':
                return result.decode()
            result.extend(b)

    def ret(value):
        u.reg_write(UC_ARM_REG_R0, value)
        u.reg_write(UC_ARM_REG_PC, u.reg_read(UC_ARM_REG_LR))

    def hook(uc, pc, size, data):
        a, b, c, d = [u.reg_read(r) for r in [UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3]]
        if pc == backend:
            keys.append(b)
            assert b in (1, 2), b
            u.mem_write(c, struct.pack('<I', 0x81234000 if b == 1 else 48000000))
            ret(1)
        elif pc == ticket:
            ret(42)
        elif pc in imports:
            name = imports[pc]
            if name == 'strncmp':
                ret(0 if string(a)[:c] == string(b)[:c] else 1)
            elif name == 'snprintf':
                assert string(c) == '%d', string(c)
                text = str(d if d < 2**31 else d - 2**32).encode()
                u.mem_write(a, text[:b-1] + b'\0')
                ret(len(text))
            elif name == '__android_log_print':
                ret(0)
            else:
                raise AssertionError('unmodeled import: ' + name)

    u.hook_add(UC_HOOK_CODE, hook)
    values, pointers = [], []
    for name in ['memory-address', 'memory-size']:
        u.mem_write(0x1001000, name.encode() + b'\0')
        for reg, val in [(UC_ARM_REG_R0, 0x1000000), (UC_ARM_REG_R1, 0x1001000),
                         (UC_ARM_REG_SP, 0x10ff000), (UC_ARM_REG_LR, 0x3fff0)]:
            u.reg_write(reg, val)
        u.emu_start(query, 0x3fff0, count=10000)
        assert u.reg_read(UC_ARM_REG_PC) == 0x3fff0
        pointer = u.reg_read(UC_ARM_REG_R0)
        pointers.append(pointer)
        values.append(string(pointer))
    assert keys == [1, 2] and values == ['-2128396288', '48000000'], (keys, values)
    assert pointers[0] == pointers[1], pointers
    return {'library': path, 'backend_ids': keys, 'returned_strings': values,
            'shared_buffer': True, 'result': 'PASS'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('libraries', nargs='+')
    print(json.dumps([run(p) for p in parser.parse_args().libraries], indent=2))
