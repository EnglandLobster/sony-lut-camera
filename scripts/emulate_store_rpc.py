#!/usr/bin/env python3
"""Execute firmware SDComIPC::sendSync ARM with synthetic transport/pthreads.
No camera/AV service or syscall is invoked. A paused cond_wait models a missing
reply, then resumes the same ARM context to test a late reply and cleanup.
"""
import argparse, hashlib, json, struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_R3,UC_ARM_REG_SP,UC_ARM_REG_LR,UC_ARM_REG_PC
REGS=[UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_R3]
SYMBOL='_ZN8SDComIPC8sendSyncEP8SDComMsgPS1_i'
def execute(path, scenario):
    u=Uc(UC_ARCH_ARM,UC_MODE_ARM);u.mem_map(0,0x100000);u.mem_map(0x1000000,0x100000)
    with path.open('rb') as f:
        e=ELFFile(f)
        for seg in e.iter_segments():
            if seg['p_type']=='PT_LOAD':u.mem_write(seg['p_vaddr'],seg.data())
        tab=e.get_section_by_name('.dynsym');symbols={s.name:s['st_value'] for s in tab.iter_symbols()}
        plt=e.get_section_by_name('.plt')['sh_addr'];imports={plt+20+12*i:tab.get_symbol(r['r_info_sym']).name for i,r in enumerate(e.get_section_by_name('.rel.plt').iter_relocations())}
    obj,wait,msg,output,stack,sentinel=0x1000000,0x1001000,0x1002000,0x1003000,0x10ff000,0xffff0
    def put(at,x):u.mem_write(at,struct.pack('<I',x&0xffffffff))
    def word(at):return struct.unpack('<I',u.mem_read(at,4))[0]
    def ret(x=0):u.reg_write(UC_ARM_REG_R0,x&0xffffffff);u.reg_write(UC_ARM_REG_PC,u.reg_read(UC_ARM_REG_LR))
    hooks=dict(imports)
    for key in ['_ZN8SDComIPC11addWaitDataEj','_ZN8SDComIPC14deleteWaitDataEj','_ZN8SDComIPC4sendEP8SDComMsg','_ZN12SDComMsgUtil16getTransactionIdEP8SDComMsgPj']:
        hooks[symbols[key]]=key
    calls=[];paused=[False];resume=[False];locks=set()
    def hook(uc,pc,size,data):
        if pc not in hooks:return
        name=hooks[pc];a,b,c,d=[u.reg_read(r) for r in REGS]
        if name=='pthread_mutex_lock':locks.add(a);ret()
        elif name=='pthread_mutex_unlock':locks.discard(a);ret()
        elif 'getTransactionId' in name:put(b,27);ret()
        elif 'addWaitData' in name:calls.append('add_wait');ret(wait)
        elif 'deleteWaitData' in name:calls.append('delete_wait');ret()
        elif 'SDComIPC4send' in name:
            calls.append('transport_send')
            if scenario=='transport_block':paused[0]=True;u.emu_stop()
            else:ret()
        elif name=='time':ret(100)
        elif name=='pthread_cond_timedwait':
            calls.append('timed_wait');assert word(c)==105;ret(110)
        elif name=='pthread_cond_wait':
            calls.append('unbounded_wait')
            if not resume[0]:paused[0]=True;u.emu_stop()
            else:put(wait,msg+0x100);ret()
        else:raise AssertionError('unmodeled call '+name)
    u.hook_add(UC_HOOK_CODE,hook);put(obj+16,3)
    for r,v in zip(REGS,[obj,msg,output,5 if scenario=='timed' else 0]):u.reg_write(r,v)
    u.reg_write(UC_ARM_REG_SP,stack);u.reg_write(UC_ARM_REG_LR,sentinel)
    u.emu_start(symbols[SYMBOL],sentinel,count=20000)
    if scenario=='timed':
        assert u.reg_read(UC_ARM_REG_PC)==sentinel and u.reg_read(UC_ARM_REG_R0)==0xfffffffb
        assert 'timed_wait' in calls and 'unbounded_wait' not in calls and not locks
    else:
        assert paused[0] and 'delete_wait' not in calls and word(output)==0
        if scenario=='missing_reply':
            assert calls[-1]=='unbounded_wait';resume[0]=True
            u.emu_start(u.reg_read(UC_ARM_REG_PC),sentinel,count=20000)
            assert u.reg_read(UC_ARM_REG_PC)==sentinel and u.reg_read(UC_ARM_REG_R0)==0
            assert word(output)==msg+0x100 and calls[-1]=='delete_wait' and not locks
    return {'scenario':scenario,'entry':hex(symbols[SYMBOL]),'calls':calls,'verified':True}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('collection',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args();reports=[]
    for row in json.loads(a.collection.read_text()):
        if row['status']!='extracted':continue
        path=Path(row['system'])/'lib/libsdcom.so'
        report={'model':row['model'],'library':'lib/libsdcom.so','sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'cases':[execute(path,s) for s in ['missing_reply','transport_block','timed']]}
        reports.append(report);print('PASS',row['model'],flush=True)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps({'kind':'original ARM control flow; transport and pthread calls mocked','models':reports},indent=2)+'\n')
if __name__=='__main__':main()
