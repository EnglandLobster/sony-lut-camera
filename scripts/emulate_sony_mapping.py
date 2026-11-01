#!/usr/bin/env python3
"""Execute real Sony ARM map/unmap control flow with synthetic OS calls.

No device files are opened and no physical memory is mapped. The Sony function
executes unchanged; only sysconf/open/mmap/munmap/close/division/logging are
implemented by the harness. Test alignment and failed-allocation cleanup.
"""
import argparse
import json
from pathlib import Path
import struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC


def run(path, scenario):
    u = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    u.mem_map(0, 0x40000)
    with path.open('rb') as stream:
        elf = ELFFile(stream)
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                u.mem_write(segment['p_vaddr'], segment.data())
        dyn = elf.get_section_by_name('.dynsym')
        symbols = {s.name: s['st_value'] for s in dyn.iter_symbols()}
        plt = elf.get_section_by_name('.plt')['sh_addr']
        imports = {plt + 20 + 12 * i: dyn.get_symbol(r['r_info_sym']).name
                   for i, r in enumerate(elf.get_section_by_name('.rel.plt').iter_relocations())}
    u.mem_map(0x1000000, 0x100000)
    address = 0x81234017 if scenario == 'unaligned' else 0x81234000
    size, base, fd = 48000000, 0x3000000, 7
    calls = []

    def ret(value):
        u.reg_write(UC_ARM_REG_R0, value & 0xffffffff)
        u.reg_write(UC_ARM_REG_PC, u.reg_read(UC_ARM_REG_LR))

    def word(at):
        return struct.unpack('<I', u.mem_read(at, 4))[0]

    def hook(uc, pc, count, data):
        if pc not in imports:
            return
        name = imports[pc]
        a, b, c, d = [u.reg_read(r) for r in [UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3]]
        if name == 'sysconf':
            ret(4096)
        elif name == 'open':
            calls.append(('open',))
            ret(-1 if scenario == 'open_failure' else fd)
        elif name == '__aeabi_uidivmod':
            u.reg_write(UC_ARM_REG_R1, a % b)
            ret(a // b)
        elif name == 'mmap':
            stack = u.reg_read(UC_ARM_REG_SP)
            assert (a, b, c, d, word(stack), word(stack + 4)) == (0, size + address % 4096, 3, 1, fd, address & ~4095)
            calls.append(('mmap', b))
            ret(-1 if scenario == 'mmap_failure' else base)
        elif name == 'munmap':
            calls.append(('munmap', a, b))
            ret(0)
        elif name == 'close':
            calls.append(('close', a))
            ret(0)
        elif name == '__android_log_print':
            ret(0)
        else:
            raise AssertionError('unmodeled import: ' + name)

    u.hook_add(UC_HOOK_CODE, hook)
    stack, output, sentinel = 0x10ff000, 0x1001000, 0x3fff0
    u.mem_write(stack, struct.pack('<III', output + 4, output + 8, output + 12))
    for reg, value in [(UC_ARM_REG_R0, 0x1000000), (UC_ARM_REG_R1, address),
                       (UC_ARM_REG_R2, size), (UC_ARM_REG_R3, output),
                       (UC_ARM_REG_SP, stack), (UC_ARM_REG_LR, sentinel)]:
        u.reg_write(reg, value)
    u.emu_start(symbols['_ZN6scalar13CameraSeqUtil15mapSharedMemoryEjjPPvS2_PjPi'], sentinel, count=10000)
    assert u.reg_read(UC_ARM_REG_PC) == sentinel
    result = u.reg_read(UC_ARM_REG_R0)
    if scenario.endswith('failure'):
        assert result == 0
        assert bytes(u.mem_read(output, 16)) == bytes(16)
        assert calls == ([('open',)] if scenario == 'open_failure' else [('open',), ('mmap', size), ('close', fd)])
    else:
        assert result == 1
        assert struct.unpack('<IIII', u.mem_read(output, 16)) == (base + address % 4096, base, size + address % 4096, fd)
        for reg, value in [(UC_ARM_REG_R0, 0x1000000), (UC_ARM_REG_R1, base),
                           (UC_ARM_REG_R2, size + address % 4096), (UC_ARM_REG_R3, fd),
                           (UC_ARM_REG_SP, stack), (UC_ARM_REG_LR, sentinel)]:
            u.reg_write(reg, value)
        u.emu_start(symbols['_ZN6scalar13CameraSeqUtil17unmapSharedMemoryEPvji'], sentinel, count=10000)
        assert u.reg_read(UC_ARM_REG_PC) == sentinel
        assert calls[-2:] == [('munmap', base, size + address % 4096), ('close', fd)]
    return {'scenario': scenario, 'result': 'PASS', 'calls': calls}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('collection', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reports = []
    for row in json.loads(args.collection.read_text()):
        if row['status'] != 'extracted':
            continue
        report = {'model': row['model'], 'scenarios': []}
        try:
            for scenario in ['aligned', 'unaligned', 'open_failure', 'mmap_failure']:
                report['scenarios'].append(run(Path(row['system']) / 'lib/libcameraseq.so', scenario))
            report['result'] = 'PASS'
        except Exception as error:
            report.update(result='FAILED_OR_UNMODELED', error=str(error))
        reports.append(report)
        print(row['model'], report['result'], report.get('error', ''), flush=True)
    args.output.write_text(json.dumps(reports, indent=2) + '\n')
    raise SystemExit(int(any(r['result'] != 'PASS' for r in reports)))
