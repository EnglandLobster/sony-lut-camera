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
    def test_unexpected_member_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/'report.zip'
            with zipfile.ZipFile(file,'w') as z:z.writestr('../private.txt','x')
            with self.assertRaises(ValueError):analyze(file)
if __name__=='__main__':unittest.main()
