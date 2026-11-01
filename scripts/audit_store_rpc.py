#!/usr/bin/env python3
"""Record source-addressed native store/IPC paths without running camera services."""
import argparse,hashlib,json,re,struct,subprocess
from pathlib import Path
from androguard.core.dex import DEX
from elftools.elf.elffile import ELFFile
from loguru import logger
logger.disable('androguard')
ROOT=Path(__file__).resolve().parents[1]
STORE='_ZN6scalar20CameraSequenceDiadem10storeImageEPNS_14DeviceMemoryIFEbb'
SEND='_ZN6scalar13CameraSeqUtil12sendSDComMsgEN15ScalarDaemonMsg10MESSAGE_IDEPKN7android6VectorIP13SDComMsgParamEEPiPS7_'
RELEASE='_ZN6scalar20CameraSequenceDiadem7releaseEv'
def inspect(row,objdump):
    root=Path(row['system']);path=root/'lib/libcameraseq.so';raw=(root/'framework/sony.cameraex.odex').read_bytes();offset,n=struct.unpack_from('<II',raw,8);dex=DEX(raw[offset:offset+n])
    cls=next(c for c in dex.get_classes() if c.get_name()=='Lcom/sony/scalar/hardware/CameraSequence;')
    methods=[m.get_name()+m.get_descriptor() for m in cls.get_methods() if 'cancel' in m.get_name().lower() or 'store' in m.get_name().lower() or 'release' in m.get_name().lower()]
    dis=subprocess.check_output([str(objdump),'-d',str(path)],text=True)
    def body(symbol):return dis.split('<'+symbol+'>:\n',1)[1].split('\n\n',1)[0]
    send=body(SEND);lines=send.splitlines();sites=[]
    for i,line in enumerate(lines):
        if 'SDComIPC8sendSync' in line:
            previous='\n'.join(lines[max(0,i-3):i+1]);assert re.search(r'mov\s+r3, #0\b',previous),previous;sites.append(previous)
    assert sites
    with path.open('rb') as f:
        table=ELFFile(f).get_section_by_name('.dynsym');symbols={s.name:hex(s['st_value']) for s in table.iter_symbols()}
    store_symbol=STORE if STORE in symbols else STORE[:-1]
    store=body(store_symbol)
    # Keep only calls and their argument setup, not the full vendor binary.
    calls=[line.strip() for line in store.splitlines() if re.search(r'\bbl\b',line) and ('sendSDComMsg' in line or 'getTicket' in line)]
    assert any('sendSDComMsg' in x for x in calls)
    return {'model':row['model'],'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'java_store_release_methods':methods,'java_cancel_method_present':any('cancel' in m.lower() for m in methods),'store_entry':symbols[store_symbol],'store_calls':calls,'send_entry':symbols[SEND],'sendSync_timeout_argument':0,'sendSync_call_evidence':sites,'release_entry':symbols[RELEASE],'release_calls':[x.strip() for x in body(RELEASE).splitlines() if re.search(r'\bbl\b',x)]}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('collection',type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--objdump',type=Path,default=ROOT/'vendor/toolchains/android-ndk-r16b/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-objdump');a=p.parse_args();reports=[]
 for row in json.loads(a.collection.read_text()):
  if row['status']=='extracted':reports.append(inspect(row,a.objdump));print('PASS store path',row['model'])
 a.output.write_text(json.dumps({'kind':'static firmware declarations and ARM argument setup','models':reports},indent=2)+'\n')
if __name__=='__main__':main()
