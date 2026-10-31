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
# Apple clang 16's ASan cannot initialize on this host; the installed LLVM runtime works.
sanitizer="${SANITIZER_CXX:-/opt/homebrew/opt/llvm/bin/clang++}"
"$sanitizer" -isysroot "$(xcrun --show-sdk-path)" -O1 -g -std=c++11 -pthread -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Inative native/core.cpp native/tests.cpp -o out/core-tests-sanitize
"$sanitizer" -isysroot "$(xcrun --show-sdk-path)" -O1 -g -std=c++11 -pthread -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Inative -Ivendor/libjpeg-turbo -Iout/jpeg-host native/core.cpp native/jpeg_io.cpp native/preview_tests.cpp out/jpeg-host/libjpeg.a -o out/preview-tests-sanitize
"$sanitizer" -isysroot "$(xcrun --show-sdk-path)" -O1 -g -std=c++11 -pthread -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Inative -Ivendor/libjpeg-turbo -Iout/jpeg-host native/core.cpp native/jpeg_io.cpp native/cli.cpp out/jpeg-host/libjpeg.a -o out/sony-lut-host-sanitize
