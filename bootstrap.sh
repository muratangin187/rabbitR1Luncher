#!/usr/bin/env bash
# bootstrap.sh — one-time setup for a fresh clone.
#
# The upstream repo documents this script but never checked it in, and
# `gradle/wrapper/gradle-wrapper.jar` is .gitignored, so `./gradlew` on a
# fresh clone dies with "Could not find or load main class
# org.gradle.wrapper.GradleWrapperMain". This regenerates it.
#
# Safe to re-run. Idempotent.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# Canonical toolchain env for this host (JAVA_HOME, ANDROID_HOME, PATH).
# Non-interactive shells don't read ~/.bashrc, so source it explicitly.
# shellcheck source=/dev/null
[ -r "$HOME/.config/android-dev.env" ] && . "$HOME/.config/android-dev.env"

GRADLE_VERSION=8.9
BOOTSTRAP_DIR="$REPO_ROOT/.bootstrap"
GRADLE_HOME="$BOOTSTRAP_DIR/gradle-$GRADLE_VERSION"

# --- 1. JDK -----------------------------------------------------------------
# Gradle 8.9 + AGP 8.7.2 want Java 17-21. mise manages it on this host.
if [ -z "${JAVA_HOME:-}" ]; then
  if command -v mise >/dev/null 2>&1 && mise where java >/dev/null 2>&1; then
    JAVA_HOME="$(mise where java)"
    export JAVA_HOME
  elif command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    export JAVA_HOME
  else
    echo "error: no JDK found. Install one:  mise use -g java@temurin-17" >&2
    exit 1
  fi
fi
echo "==> JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# --- 2. Android SDK ---------------------------------------------------------
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK_DIR/platforms" ]; then
  echo "error: no Android SDK at $SDK_DIR" >&2
  echo "       install cmdline-tools, then:" >&2
  echo "       sdkmanager 'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'" >&2
  exit 1
fi
if [ ! -f local.properties ]; then
  echo "sdk.dir=$SDK_DIR" > local.properties
  echo "==> wrote local.properties (sdk.dir=$SDK_DIR)"
fi

# --- 3. Signing key ---------------------------------------------------------
# Both debug AND release build types sign with the CarrotOS platform key, so
# the APK's signature matches the prebuilt at /system/app/R1Launcher/ and
# `adb install -r` can replace it in place. This is the *public AOSP test key*
# (subject CN=Android, notBefore 2008) — not a secret, but not in git either.
if [ ! -f platform.keystore ]; then
  echo "==> platform.keystore missing — fetching from the CarrotOS harness"
  TMP="$(mktemp -d)"
  curl -fsSL -o "$TMP/k" \
    https://raw.githubusercontent.com/khalifa007/carrotOS-harness/main/keys/platform.keystore
  mv "$TMP/k" platform.keystore
  rm -rf "$TMP"
fi
FPR="$(openssl pkcs12 -in platform.keystore -nokeys -passin pass:android 2>/dev/null \
       | openssl x509 -noout -fingerprint -sha256 | tr -d ':' | cut -d= -f2 | tr 'A-Z' 'a-z')"
EXPECTED=c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
if [ "$FPR" != "$EXPECTED" ]; then
  echo "error: platform.keystore fingerprint mismatch" >&2
  echo "       got      $FPR" >&2
  echo "       expected $EXPECTED" >&2
  echo "       A wrong key means INSTALL_FAILED_UPDATE_INCOMPATIBLE on device." >&2
  exit 1
fi
echo "==> platform.keystore OK ($EXPECTED)"

# --- 4. Gradle wrapper jar --------------------------------------------------
if [ -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "==> gradle-wrapper.jar already present"
else
  if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "==> downloading Gradle $GRADLE_VERSION"
    mkdir -p "$BOOTSTRAP_DIR"
    curl -fsSL -o "$BOOTSTRAP_DIR/gradle.zip" \
      "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    (cd "$BOOTSTRAP_DIR" && { bsdtar -xf gradle.zip || unzip -q gradle.zip; })
    rm -f "$BOOTSTRAP_DIR/gradle.zip"
  fi
  echo "==> generating gradle/wrapper/gradle-wrapper.jar"
  "$GRADLE_HOME/bin/gradle" wrapper \
    --gradle-version "$GRADLE_VERSION" --distribution-type bin -q
fi
chmod +x gradlew

echo
echo "Bootstrap complete. Next:"
echo "  ./r1.sh build     # assembleDebug"
echo "  ./r1.sh           # build + install + restart on a connected R1"
