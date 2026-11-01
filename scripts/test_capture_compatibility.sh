#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/compatibility-tests
clang++ -std=c++11 native/device_memory_api_tests.cpp -o out/compatibility-tests/memory-api
clang++ -std=c++11 native/device_memory_span_tests.cpp -o out/compatibility-tests/memory-span
out/compatibility-tests/memory-api
out/compatibility-tests/memory-span
bash scripts/test_legacy_capture.sh
python3 scripts/generate_preview_fixture.py
python3 - <<'PY'
from pathlib import Path
p=Path('out/compatibility-tests/COLOR33.CUB')
with p.open('w') as f:
    f.write('LUT_3D_SIZE 33\n')
    for b in range(33):
        for g in range(33):
            for r in range(33):
                f.write('%.9f %.9f %.9f\n' % ((r/32)**1.2, (g/32)**0.9, (b/32)**1.1))
PY
clang++ -O2 -std=c++11 -pthread -Inative native/core.cpp native/uyvy.cpp native/uyvy_model_tests.cpp -o out/compatibility-tests/model-tests
python3 - <<'PY'
import json,subprocess
from pathlib import Path
out=Path('out/compatibility-tests')
# Synthetic geometry cases, not claims about camera parameter availability.
cases=[(4240,2832,32,4),(4912,3264,8,1),(5184,3888,8,2),
       (5456,3632,8,2),(5472,3648,32,4),(6000,4000,32,4),
       (7360,4912,16,4),(7952,5304,16,4),(6000,4000,1,4)]
results=[]
for case in cases:
    result=subprocess.run([str(out/'model-tests'),str(out/'COLOR33.CUB'),*map(str,case)],check=True,capture_output=True,text=True)
    row=json.loads(result.stdout);results.append(row);print(result.stdout.strip(),flush=True)
(out/'geometry-results.json').write_text(json.dumps(results,indent=2)+'\n')
PY
