#!/usr/bin/env python3
"""Copy one SONYLUT run from a mounted camera card; never writes to the card."""
import argparse,datetime,json,pathlib,shutil

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('volume',type=pathlib.Path,help='Camera card mount, e.g. /Volumes/SONY64')
p.add_argument('--run',help='Optional R... directory; defaults to most recently modified run')
a=p.parse_args()
root=a.volume/'SONYLUT'
if a.run:
    if pathlib.Path(a.run).name!=a.run:raise SystemExit('run must be a single directory name')
    source=root/a.run
else:
    runs=sorted((x for x in root.glob('R*') if x.is_dir()),key=lambda x:x.stat().st_mtime_ns)
    if not runs:raise SystemExit('No SONYLUT test run on this volume')
    source=runs[-1]
if not source.is_dir():raise SystemExit('Requested test run does not exist')
dest=pathlib.Path(__file__).resolve().parents[1]/'results'/('usb-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
shutil.copytree(source,dest)
state=(dest/'STATE.TXT').read_text().strip() if (dest/'STATE.TXT').is_file() else 'STATE missing'
records=[]
log=dest/'RESULTS.JSN'
if not log.exists():log=dest/'RESULTS.JSONL'  # prior host fixtures, before Sony 8.3 fix
if log.is_file():
    for line in log.read_text().splitlines():
        try:record=json.loads(line)
        except ValueError:continue
        # The app exports its cumulative private journal; select the current run.
        if record.get('kind')=='usb_start':records=[]
        records.append(record)
summary={'source_run':source.name,'state':state,'jpeg':[],'preview':[],'errors':[]}
for r in records:
    if r.get('kind')=='jpeg':summary['jpeg'].append(r)
    if r.get('kind')=='preview_end':summary['preview'].append(r)
    if r.get('kind')=='error':summary['errors'].append(r)
(dest/'SUMMARY.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print(dest);print('State:',state)
for r in summary['jpeg']:
    s=r['stats'];print(r.get('output','JPEG'),s['width'],s['height'],'total_ms=',s['total_ms'],'LUT_ms=',s['lut_ms'])
for r in summary['preview']:
    print('Preview frames=',r['frames'],'elapsed_ms=',r['elapsed_ms'],'fps=',round(r['frames']*1000/max(1,r['elapsed_ms']),2))
for r in summary['errors']:print('ERROR',r.get('stage'),r.get('message'))
