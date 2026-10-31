#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/lut-cache-tests
python3 - <<'PY'
from pathlib import Path
p=Path('out/lut-cache-tests/GRID65.CUB')
with p.open('w') as f:
    f.write('LUT_3D_SIZE 65\nDOMAIN_MIN -0.2 0.1 0\nDOMAIN_MAX 1.2 0.9 1\n')
    for b in range(65):
        for g in range(65):
            for r in range(65): f.write(f'{r/64-.1:.8f} {g/64:.8f} {b/64+.1:.8f}\n')
PY
flags=(-std=c++11 -O2 -pthread)
compiler="${CXX:-clang++}"
if [[ "${SANITIZE:-0}" == 1 ]]; then
  # Match build_host.sh: Apple clang's ASan cannot initialize on this macOS.
  compiler="${SANITIZER_CXX:-/opt/homebrew/opt/llvm/bin/clang++}"
  flags+=(-isysroot "$(xcrun --show-sdk-path)" -fsanitize=address,undefined -fno-omit-frame-pointer)
fi
"$compiler" "${flags[@]}" native/core.cpp native/lut_cache.cpp native/lut_cache_tests.cpp -o out/lut-cache-tests/run
out/lut-cache-tests/run out/lut-cache-tests out/tests/ID33.CUB out/tests/DOMAIN.CUB out/tests/COLOR17.CUB out/lut-cache-tests/GRID65.CUB android-assets/COLOR33.CUB
