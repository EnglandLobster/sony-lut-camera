#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out
python="${PYTHON:-python3}"
"${CXX:-clang++}" -O3 -std=c++11 -pthread -Inative native/core.cpp native/preview_fit.cpp native/preview_fit_cli.cpp -o out/preview-fit-host
"$python" scripts/test_preview_fit.py out/preview-fit-host
