#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out
cmake -S vendor/libjpeg-turbo -B out/jpeg-host -DENABLE_SHARED=OFF -DENABLE_STATIC=ON \
  -DWITH_SIMD=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 > out/jpeg-config.log 2>&1
cmake --build out/jpeg-host --target jpeg-static -j4 > out/jpeg-build.log 2>&1
flags=(-O3 -std=c++11 -pthread -fno-vectorize -fno-slp-vectorize -Inative -Ivendor/libjpeg-turbo -Iout/jpeg-host)
clang++ "${flags[@]}" native/core.cpp native/jpeg_io.cpp native/cli.cpp out/jpeg-host/libjpeg.a -o out/sony-lut-host
clang++ "${flags[@]}" native/core.cpp native/tests.cpp -o out/core-tests
clang++ "${flags[@]}" native/core.cpp native/jpeg_io.cpp native/preview_tests.cpp out/jpeg-host/libjpeg.a -o out/preview-tests
sanitize=(-O1 -g -std=c++11 -pthread -fsanitize=address,undefined -fno-omit-frame-pointer)
if [[ "$(uname -s)" == Darwin ]]; then
  # Apple clang 16's ASan cannot initialize on macOS; the Homebrew LLVM runtime works.
  sanitizer="${SANITIZER_CXX:-/opt/homebrew/opt/llvm/bin/clang++}"
  sanitize=(-isysroot "$(xcrun --show-sdk-path)" "${sanitize[@]}")
else
  sanitizer="${SANITIZER_CXX:-clang++}"
fi
"$sanitizer" "${sanitize[@]}" \
  -Inative native/core.cpp native/tests.cpp -o out/core-tests-sanitize
"$sanitizer" "${sanitize[@]}" \
  -Inative -Ivendor/libjpeg-turbo -Iout/jpeg-host native/core.cpp native/jpeg_io.cpp native/preview_tests.cpp out/jpeg-host/libjpeg.a -o out/preview-tests-sanitize
"$sanitizer" "${sanitize[@]}" \
  -Inative -Ivendor/libjpeg-turbo -Iout/jpeg-host native/core.cpp native/jpeg_io.cpp native/cli.cpp out/jpeg-host/libjpeg.a -o out/sony-lut-host-sanitize
