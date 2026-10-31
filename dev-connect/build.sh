#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}"
bt="$sdk/build-tools/36.0.0"
jar="$sdk/platforms/android-36/android.jar"
java_home="$(/usr/libexec/java_home -v 17)"
export JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH"
mkdir -p out/dev-connect/classes out/dev-connect/dex out/dev-connect/tests out/dev-connect/res/drawable dist
python3 - <<'PY'
import zipfile
from PIL import Image,ImageDraw
im=Image.new('RGB',(72,72),(24,66,118));draw=ImageDraw.Draw(im)
draw.arc((7,14,65,70),205,335,fill='white',width=5)
draw.arc((18,27,54,63),205,335,fill='white',width=5)
draw.ellipse((31,49,41,59),fill='white')
im.save('out/dev-connect/res/drawable/icon.png')
# Reuse the verified v0.11 bridge and adbd, byte-for-byte. No firmware edits.
with zipfile.ZipFile('vendor/packages/OpenMemories-Tweak-release-0.11.apk') as src:
    for name in ('libtweak.so','libadbd.so'):
        target='out/dev-connect/'+name
        with open(target,'wb') as out:out.write(src.read('lib/armeabi/'+name))
PY
javac --release 7 -Xlint:-options -d out/dev-connect/tests dev-connect/src/org/hongen/sonydevconnect/WpaProfile.java dev-connect/tests/WpaProfileTest.java
java -cp out/dev-connect/tests org.hongen.sonydevconnect.WpaProfileTest
javac --release 7 -Xlint:-options -d out/dev-connect/tests dev-connect/src/org/hongen/sonydevconnect/ConnectionGate.java dev-connect/src/org/hongen/sonydevconnect/AdbShutdown.java dev-connect/tests/DebugLifecycleTest.java
java -cp out/dev-connect/tests org.hongen.sonydevconnect.DebugLifecycleTest
javac --release 7 -Xlint:-options -d out/dev-connect/tests dev-connect/src/org/hongen/sonydevconnect/ExitVisit.java dev-connect/tests/ExitVisitTest.java
java -cp out/dev-connect/tests org.hongen.sonydevconnect.ExitVisitTest
"$bt/aapt2" compile --dir out/dev-connect/res -o out/dev-connect/res.zip
"$bt/aapt2" compile --dir dev-connect/res -o out/dev-connect/app-res.zip
"$bt/aapt2" link -o out/dev-connect/unsigned.apk --manifest dev-connect/AndroidManifest.xml -I "$jar" --min-sdk-version 10 --target-sdk-version 10 -A dev-connect/assets out/dev-connect/res.zip out/dev-connect/app-res.zip
find dev-connect/src -name '*.java' > out/dev-connect/sources.txt
javac -encoding UTF-8 --release 7 -Xlint:-options -cp "$jar" -d out/dev-connect/classes @out/dev-connect/sources.txt
find out/dev-connect/classes -name '*.class' > out/dev-connect/classes.txt
java -cp "$bt/lib/d8.jar" com.android.tools.r8.D8 --min-api 10 --lib "$jar" --output out/dev-connect/dex @out/dev-connect/classes.txt
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('out/dev-connect/unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
    z.write('out/dev-connect/dex/classes.dex','classes.dex')
    for name in ('libtweak.so','libadbd.so'):z.write('out/dev-connect/'+name,'lib/armeabi/'+name)
PY
"$bt/zipalign" -f 4 out/dev-connect/unsigned.apk out/dev-connect/aligned.apk
"$bt/apksigner" sign --ks out/test.keystore --ks-pass pass:android --key-pass pass:android --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false --out dist/SonyDevConnect.apk out/dev-connect/aligned.apk
"$bt/apksigner" verify --min-sdk-version 10 --verbose dist/SonyDevConnect.apk
"$bt/aapt" dump badging dist/SonyDevConnect.apk
python3 - <<'PY'
import zipfile,hashlib,json
p='dist/SonyDevConnect.apk'
with zipfile.ZipFile(p) as z:
    assert z.read('classes.dex')[:8]==b'dex\n035\x00'
    assert z.testzip() is None
    print(json.dumps({'artifact':p,'bytes':len(open(p,'rb').read()),'sha256':hashlib.sha256(open(p,'rb').read()).hexdigest(),'dex':'035','embedded_network_credentials':False}))
PY
