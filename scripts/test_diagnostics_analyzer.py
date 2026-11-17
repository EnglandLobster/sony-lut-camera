import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from analyze_diagnostics import analyze

class AnalyzerTests(unittest.TestCase):
    def test_mirrored_interrupted_session(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'report.zip'
            events=[dict(schema=1,session='capture',seq=0,event='session_header',detail='model=test',role='CAP'),dict(schema=1,session='capture',seq=1,event='camera_open_begin',detail='',role='CAP')]
            text='\n'.join(map(json.dumps,events))+'\n{"partial"'
            with zipfile.ZipFile(file,'w') as z:
                z.writestr('SUMMARY.TXT','test');z.writestr('internal/CAP0.TXT',text);z.writestr('sd/CAP0.TXT',text)
            result=analyze(file)
            self.assertEqual(result['sessions'][0]['events'],2)
            self.assertEqual(result['sessions'][0]['outcome'],'no close recorded')
            self.assertEqual(result['malformed_lines'],2)
    def test_power_events_previous_session_and_empty_journals(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'report.zip'
            names=['session_header','previous_session','activity_pause','system_suspend','system_resume','closed']
            details=['boot_id=b','previous=journal bytes=10 same_boot=false closed=false last_event=store_wait','PhotoActivity finishing=false','auto_power_off=10','','']
            events=[dict(schema=1,session='s',seq=i,uptime_ms=i,event=n,detail=d,role='CAP') for i,(n,d) in enumerate(zip(names,details))]
            with zipfile.ZipFile(file,'w') as z:
                z.writestr('SUMMARY.TXT','test');z.writestr('sd/CAP0.TXT','\n'.join(map(json.dumps,events)));z.writestr('sd/CAP1.TXT','')
            result=analyze(file)
            session=result['sessions'][0]
            self.assertEqual(session['previous_session'],details[1])
            self.assertEqual([e['event'] for e in session['power_events']],['activity_pause','system_suspend','system_resume'])
            self.assertEqual(result['empty_members'],['sd/CAP1.TXT'])
    def test_unexpected_member_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'report.zip'
            with zipfile.ZipFile(file,'w') as z:z.writestr('../private.txt','x')
            with self.assertRaises(ValueError):analyze(file)
if __name__=='__main__':unittest.main()
