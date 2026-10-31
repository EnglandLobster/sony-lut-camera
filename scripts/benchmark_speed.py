#!/usr/bin/env python3
"""Explicit on-camera CLI comparison; writes only a new /data/local/tmp subdirectory.
Does not install an APK, trigger a shutter, change camera settings, or touch DCIM.
"""
import argparse,datetime,json,pathlib,re,subprocess,time
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--input',required=True,type=pathlib.Path,help='Local captured JPEG, reused unchanged for every case')
p.add_argument('--out',type=pathlib.Path,default=ROOT/'results/speed-20261018')
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
def adb(*args,timeout=120):
 r=subprocess.run(['adb','-s',a.serial,*map(str,args)],capture_output=True,text=True,timeout=timeout)
 if r.returncode:raise RuntimeError(r.stderr or r.stdout)
 return r.stdout.replace('\r','').strip()
if adb('get-state',timeout=8)!='device':raise RuntimeError('camera not online')
remote='/data/local/tmp/S'+format(time.time_ns()&0x0fffffff,'07X')
adb('shell','mkdir',remote)
# Sony's internal filesystem assigns shell-created directories to system.
# ADB sync runs as shell, so grant access only to this fresh staging directory.
adb('shell','chmod','777',remote)
assets=[(ROOT/'results/speed-20261018/sony-lut-baseline-armv7','BASE'),(ROOT/'dist/sony-lut-armv7','NEW'),(ROOT/'out/tests/COLOR17.CUB','COLOR.CUB'),(a.input,'INPUT.JPG')]
for local,name in assets:adb('push',local,remote+'/'+name)
adb('shell','chmod','755',remote+'/BASE',remote+'/NEW')
report={'serial':a.serial,'input':str(a.input.resolve()),'remote':remote,'cases':[],'apk_installed':False,'camera_settings_changed':False}
for label,exe,workers,pipeline in [('BASE','BASE',4,None),('BUF','NEW',4,0),('PIPE2','NEW',2,1),('PIPE4','NEW',4,1)]:
 target=remote+'/'+label+'.JPG';args=[remote+'/'+exe,remote+'/COLOR.CUB',remote+'/INPUT.JPG',target,workers,1,92,64]
 if pipeline is not None:args.append(pipeline)
 raw=adb('shell',*args,timeout=150)
 try:stats=json.loads(raw)
 except ValueError:raise RuntimeError(raw)
 adb('shell','chmod','644',target)
 md5=adb('shell','md5',target).split()[0]
 if not re.fullmatch('[0-9a-fA-F]{32}',md5):raise RuntimeError('unexpected device md5 output')
 row={'case':label,'md5':md5,**stats};report['cases'].append(row)
 (a.out/'DEVICE_SPEED.json').write_text(json.dumps(report,indent=2))
 print(json.dumps(row),flush=True)
report['all_output_bytes_equivalent']=len({c['md5'] for c in report['cases']})==1
if not report['all_output_bytes_equivalent']:raise RuntimeError('camera outputs differ; do not promote pipeline')
report['recorded_at']=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat()
(a.out/'DEVICE_SPEED.json').write_text(json.dumps(report,indent=2))
print('PASS: identical outputs; staged files retained at '+remote,flush=True)
