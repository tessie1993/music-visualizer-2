#!/usr/bin/env bash
#
# Installs the Android SDK a MusicViz Gradle build needs, for environments that
# do not ship one — chiefly Claude Code cloud sessions, whose image has a JDK
# and Gradle but no SDK at all. GitHub Actions does not need this: the runner
# image already carries an SDK and android.yml only adds packages to it.
#
# Point a cloud environment's setup script at this file:
#
#   bash musicviz-project/musicviz/tools/setup-android-sdk.sh
#
# It is idempotent, so re-running it on a warm container costs one sdkmanager
# no-op rather than a re-download.
#
# NETWORK: every SDK package comes from dl.google.com, which is NOT in the
# "Trusted" allowlist of a Claude Code cloud environment. The environment has
# to be set to Full network access, or to Custom with dl.google.com listed, or
# the download below fails with a proxy 403. The preflight check says so
# outright instead of letting sdkmanager fail with a bare stack trace.
#
# Overridable inputs:
#   ANDROID_SDK_ROOT     where to install          (default $HOME/android-sdk)
#   ANDROID_BUILD_TOOLS  build-tools version       (default <compileSdk>.0.0)
#   ANDROID_PLATFORM_MINOR  platform minor release (default 0)
#   CMDLINE_TOOLS_ZIP    cmdline-tools archive URL (default below)
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_ZIP="${CMDLINE_TOOLS_ZIP:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"

# Read compileSdk from the build file rather than hardcoding it a second time:
# the whole point of this script is to install the SDK the build asks for, and
# a copy that drifts installs the wrong one silently.
COMPILE_SDK="$(sed -n 's/^[[:space:]]*compileSdk = \([0-9]\{1,\}\).*/\1/p' \
  "$PROJECT_DIR/app/build.gradle.kts" | head -n 1)"
if [ -z "$COMPILE_SDK" ]; then
  echo "setup-android-sdk: could not read compileSdk from app/build.gradle.kts" >&2
  exit 1
fi
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$COMPILE_SDK.0.0}"

# From API 37 Android ships MINOR platform releases and the bare package id is
# gone: there is no "platforms;android-37", only android-37.0 and android-37.1.
# So the platform id needs the minor component appended, which is why this is
# not just "android-$COMPILE_SDK" the way build-tools still is.
PLATFORM_MINOR="${ANDROID_PLATFORM_MINOR:-0}"
PLATFORM="platforms;android-$COMPILE_SDK.$PLATFORM_MINOR"

echo "setup-android-sdk: platform=$PLATFORM build-tools=$BUILD_TOOLS root=$SDK_ROOT"

# --- Preflight: fail on the egress policy, not 200 lines into sdkmanager -----
if ! curl -fsS --max-time 30 -o /dev/null --range 0-0 "$CMDLINE_TOOLS_ZIP"; then
  cat >&2 <<EOF
setup-android-sdk: cannot reach dl.google.com.

Every Android SDK package is served from dl.google.com, and maven.google.com
redirects there too, so a blocked host stops both the SDK install and Gradle's
plugin resolution. In a Claude Code cloud session this shows up as a proxy 403
("CONNECT tunnel failed"), which means the environment's network policy, not a
broken mirror — retrying will not help.

Fix it on the environment, then start a NEW session (an environment edit does
not reach a session that is already running):

  claude.ai/code -> cloud icon above the message box -> hover the environment
  -> settings icon -> Network access

  - Full, or
  - Custom with these lines under "Allowed domains", leaving "Also include
    default list of common package managers" checked:

      dl.google.com
      maven.google.com
EOF
  exit 1
fi

# --- cmdline-tools ----------------------------------------------------------
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "setup-android-sdk: installing cmdline-tools"
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "$TMP_DIR"' EXIT
  curl -fsSL -o "$TMP_DIR/cmdline-tools.zip" "$CMDLINE_TOOLS_ZIP"
  unzip -q "$TMP_DIR/cmdline-tools.zip" -d "$TMP_DIR"
  # The archive unpacks to cmdline-tools/, but sdkmanager only resolves its own
  # SDK root when it sits in cmdline-tools/latest/.
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$TMP_DIR/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

# --- packages ---------------------------------------------------------------
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "$PLATFORM" \
  "build-tools;$BUILD_TOOLS"

# --- hand the location to Gradle --------------------------------------------
# local.properties, not an export: a setup script's environment does not reach
# the shells Claude Code opens later, but every Gradle invocation reads this
# file. It is git-ignored.
echo "sdk.dir=$SDK_ROOT" > "$PROJECT_DIR/local.properties"

if ! java -version 2>&1 | grep -q '"25'; then
  echo "setup-android-sdk: note — CI builds on JDK 25; this machine has" \
    "$(java -version 2>&1 | head -n 1). Set org.gradle.java.home if the" \
    "build disagrees with CI." >&2
fi

echo "setup-android-sdk: done — sdk.dir=$SDK_ROOT written to local.properties"
