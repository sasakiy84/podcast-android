#!/usr/bin/env bash
# Reproduce the cloud toolchain without changing dependency declarations or lockfiles.
set -euo pipefail
base=/workspace/.android-dev
mkdir -p "$base/downloads" "$base/sdk" "$base/gradle-home"
if [[ ! -x "$base/jdk-21.0.13+9/bin/javac" ]]; then
  curl -fsSL --retry 2 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz' -o "$base/downloads/jdk21.tar.gz"
  python3 - <<'PYJDK'
from pathlib import Path
import hashlib
import tarfile
archive = Path('/workspace/.android-dev/downloads/jdk21.tar.gz')
if hashlib.sha256(archive.read_bytes()).hexdigest() != 'd7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c':
    raise SystemExit('Temurin JDK checksum mismatch')
with tarfile.open(archive) as bundle:
    bundle.extractall('/workspace/.android-dev', filter='data')
PYJDK
fi
export JAVA_HOME="$base/jdk-21.0.13+9"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_USER_HOME="$base/android-user-home"
mkdir -p "$ANDROID_USER_HOME"
export ANDROID_HOME="$base/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$base/gradle-home"

if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fsSL --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip -o "$base/downloads/cmdline-tools.zip"
  python3 - <<'PY'
from pathlib import Path
import hashlib
import zipfile
base = Path('/workspace/.android-dev')
archive = base / 'downloads/cmdline-tools.zip'
if hashlib.sha1(archive.read_bytes()).hexdigest() != 'e025545c62a8e64c7559119566a569fb1dec5f60':
    raise SystemExit('SDK archive checksum mismatch against Google repository metadata')
with zipfile.ZipFile(archive) as bundle:
    bundle.extractall(base / 'sdk/cmdline-tools')
(base / 'sdk/cmdline-tools/cmdline-tools').rename(base / 'sdk/cmdline-tools/latest')
for executable in (base / 'sdk/cmdline-tools/latest/bin').iterdir():
    executable.chmod(0o755)
PY
fi

# Use the session CA and proxy; credentials are never written to this configuration.
python3 - <<'PY'
from pathlib import Path
import os
import shutil
import subprocess
import urllib.parse
base = Path('/workspace/.android-dev')
java = Path(shutil.which('java')).resolve()
cacerts = java.parent.parent / 'lib/security/cacerts'
shutil.copyfile(cacerts, base / 'java-cacerts')
ca = os.environ.get('CODEX_PROXY_CERT')
if ca:
    subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'codex-environment-proxy',
                    '-file', ca, '-keystore', str(base / 'java-cacerts'), '-storepass', 'changeit'], check=True)
properties = [f'systemProp.javax.net.ssl.trustStore={base}/java-cacerts']
for scheme in ('http', 'https'):
    proxy = os.environ.get(scheme.upper() + '_PROXY')
    if proxy:
        url = urllib.parse.urlsplit(proxy)
        if url.username or url.password:
            raise SystemExit('Authenticated proxy needs runtime configuration; do not save credentials')
        properties += [f'systemProp.{scheme}.proxyHost={url.hostname}',
                       f'systemProp.{scheme}.proxyPort={url.port or 8080}']
properties.append('systemProp.http.nonProxyHosts=localhost|127.*')
(base / 'gradle-home/gradle.properties').write_text('\n'.join(properties) + '\n')
(base / 'env.sh').write_text('''export JAVA_HOME=/workspace/.android-dev/jdk-21.0.13+9
export ANDROID_USER_HOME=/workspace/.android-dev/android-user-home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/workspace/.android-dev/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=/workspace/.android-dev/gradle-home
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
''')
PY

if [[ ! -f "$ANDROID_HOME/platforms/android-36/android.jar" || ! -x "$ANDROID_HOME/build-tools/36.0.0/apksigner" || ! -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    'platforms;android-36' 'build-tools;36.0.0' platform-tools
fi

source "$base/env.sh"
cd /workspace/podcast-android
java -version
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
