#!/bin/bash
# Сборка APK без Gradle: aapt2 + javac + d8. → out/claudedroid.apk
set -e
M=$(cd "$(dirname "$0")" && pwd)

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for c in ~/Library/Android/sdk ~/Android/Sdk /usr/local/lib/android/sdk; do
  [ -d "$c" ] && SDK=$c && break
done
[ -d "$SDK" ] || { echo "не нашёл Android SDK — задай ANDROID_HOME" >&2; exit 1; }

BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
AJ=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
[ -x "$BT/aapt2" ] || { echo "нет build-tools в $SDK" >&2; exit 1; }
[ -f "$AJ" ] || { echo "нет ни одной platform в $SDK" >&2; exit 1; }

# отладочный ключ: тот же, которым подписывает Android Studio; создаём, если его нет.
# Своим ключом — KEYSTORE=/путь/к.keystore bash build.sh (пароль всегда android).
# Подпись другим ключом = adb install -r откажет: сначала снести старую установку.
KS=${KEYSTORE:-~/.android/debug.keystore}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
    -keyalg RSA -keysize 2048 -validity 10950
fi

rm -rf "$M/out"; mkdir -p "$M/out/classes" "$M/out/dex" "$M/out/gen"
"$BT/aapt2" compile --dir "$M/res" -o "$M/out/res.zip"
"$BT/aapt2" link -o "$M/out/base.apk" --manifest "$M/AndroidManifest.xml" -A "$M/assets" -I "$AJ" --java "$M/out/gen" "$M/out/res.zip"
javac -source 17 -target 17 -cp "$AJ" -d "$M/out/classes" "$M"/src/sh/kirill/claudedroid/*.java "$M"/out/gen/sh/kirill/claudedroid/R.java
"$BT/d8" --release --lib "$AJ" --output "$M/out/dex" $(find "$M/out/classes" -name '*.class')
cd "$M/out" && cp dex/classes.dex . && zip -q base.apk classes.dex
"$BT/zipalign" -f 4 "$M/out/base.apk" "$M/out/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$M/out/claudedroid.apk" "$M/out/aligned.apk"
ls -la "$M/out/claudedroid.apk"
echo "подписан ключом $KS"
