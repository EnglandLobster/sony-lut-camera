#!/usr/bin/env python3
"""Tomorrow's read-only collector. Never installs, launches, roots or changes settings."""
import argparse,datetime,json,pathlib,subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True,help='Exact A6300 serial from adb devices -l')
p.add_argument('--out',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[1]/'results')
a=p.parse_args()
dest=a.out/datetime.datetime.now().strftime('%Y%m%d-%H%M%S');dest.mkdir(parents=True,exist_ok=False)
commands={
 'GETPROP.TXT':['getprop'],
 'CPUINFO.TXT':['cat','/proc/cpuinfo'],
 'MEMINFO.TXT':['cat','/proc/meminfo'],
 'PS.TXT':['ps'],
 'APP-MEMORY.TXT':['dumpsys','meminfo','org.hongen.sonylutprobe'],
 'LOGCAT.TXT':['logcat','-d','-v','threadtime','-t','400'],
 'SONYLUT-LOGCAT.TXT':['logcat','-d','-v','brief','-s','SonyLutProbe:I','*:S'],
}
for name in ('PROBE.TXT','PARAMS.TXT','RESULTS.JSONL'):
    commands[name]=['run-as','org.hongen.sonylutprobe','cat','files/'+name]
status={}
for name,cmd in commands.items():
    try:
        r=subprocess.run(['adb','-s',a.serial,'shell']+cmd,capture_output=True,timeout=20)
        (dest/name).write_bytes(r.stdout.replace(b'\r\n',b'\n'))
        status[name]={'returncode':r.returncode,'stderr':r.stderr.decode(errors='replace')}
        if r.stdout.startswith(b'run-as:'):
            status[name]['error']=r.stdout.decode(errors='replace').strip()
    except (OSError,subprocess.TimeoutExpired) as e:status[name]={'error':str(e)}
(dest/'COLLECTION.json').write_text(json.dumps(status,indent=2),encoding='utf8')
print(dest)
