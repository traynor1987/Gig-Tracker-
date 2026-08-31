#!/usr/bin/env bash
set -euo pipefail

sdk_root_path="${1:?Usage: ./build-debug.sh /absolute/path/to/android-sdk}"
project_root_path="$(cd "$(dirname "$0")" && pwd)"
app_root_path="$project_root_path/app"
build_root_path="$app_root_path/build/manual-debug"
output_root_path="$app_root_path/build/outputs/apk/release"
android_jar_path="$sdk_root_path/platforms/android-35/android.jar"
build_tools_path="$sdk_root_path/build-tools/35.0.0"

if [[ ! -f "$android_jar_path" ]]; then
  echo "Android API 35 is missing at $android_jar_path" >&2
  exit 2
fi

mkdir -p "$build_root_path/compiled-res" "$build_root_path/generated" "$build_root_path/classes" "$build_root_path/dex" "$output_root_path"

"$build_tools_path/aapt2" compile --dir "$app_root_path/src/main/res" -o "$build_root_path/compiled-res/resources.zip"
"$build_tools_path/aapt2" link \
  -o "$build_root_path/app-unsigned-unaligned.apk" \
  --manifest "$app_root_path/src/main/AndroidManifest.xml" \
  --java "$build_root_path/generated" \
  --custom-package site.chatgpt.traynor1987.gigtracker \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 3 \
  --version-name 1.2.0 \
  -I "$android_jar_path" \
  "$build_root_path/compiled-res/resources.zip"

find "$app_root_path/src/main/java" "$build_root_path/generated" -name '*.java' -print > "$build_root_path/java-sources.txt"
javac -source 17 -target 17 -Xlint:deprecation \
  -classpath "$android_jar_path" \
  -d "$build_root_path/classes" \
  @"$build_root_path/java-sources.txt"

find "$build_root_path/classes" -name '*.class' -print > "$build_root_path/class-files.txt"
"$build_tools_path/d8" \
  --lib "$android_jar_path" \
  --min-api 26 \
  --output "$build_root_path/dex" \
  @"$build_root_path/class-files.txt"

cp "$build_root_path/app-unsigned-unaligned.apk" "$build_root_path/app-with-dex.apk"
zip -q -j "$build_root_path/app-with-dex.apk" "$build_root_path/dex/classes.dex"
"$build_tools_path/zipalign" -f 4 "$build_root_path/app-with-dex.apk" "$build_root_path/app-aligned.apk"

debug_keystore_path="${GIG_TRACKER_SIGNING_KEYSTORE_PATH:-${GIG_TRACKER_DEBUG_KEYSTORE_PATH:-$build_root_path/debug.keystore}}"
signing_alias="${GIG_TRACKER_SIGNING_ALIAS:-androiddebugkey}"
signing_store_password="${GIG_TRACKER_SIGNING_STORE_PASSWORD:-android}"
signing_key_password="${GIG_TRACKER_SIGNING_KEY_PASSWORD:-$signing_store_password}"
mkdir -p "$(dirname "$debug_keystore_path")"
if [[ ! -f "$debug_keystore_path" ]]; then
  keytool -genkeypair -keystore "$debug_keystore_path" -storepass "$signing_store_password" -keypass "$signing_key_password" \
    -alias "$signing_alias" -dname "CN=Gig Tracker,O=Gig Tracker,C=GB" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$build_tools_path/apksigner" sign \
  --ks "$debug_keystore_path" \
  --ks-key-alias "$signing_alias" \
  --ks-pass pass:"$signing_store_password" \
  --key-pass pass:"$signing_key_password" \
  --out "$output_root_path/app-release.apk" \
  "$build_root_path/app-aligned.apk"

"$build_tools_path/apksigner" verify --verbose "$output_root_path/app-release.apk"
echo "$output_root_path/app-release.apk"
