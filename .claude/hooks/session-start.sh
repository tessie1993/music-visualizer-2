#!/bin/bash
# SessionStart hook for Claude Code on the web: provision the Android SDK so
# the Gradle gates (:app:testDebugUnitTest, :app:lintDebug, :app:ktlintCheck)
# work from the first prompt. Local sessions are left alone - a developer's
# machine already has its own SDK and local.properties.
#
# Synchronous on purpose: the very first thing a web session is usually asked
# to do is run the tests, and an async install would race that. After the
# first run the container state is cached, so this is a no-op costing a
# couple of directory checks.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

"$CLAUDE_PROJECT_DIR/tools/setup-android-sdk.sh"

# Make the SDK visible to anything that reads the environment rather than
# local.properties (avdmanager, adb, scripts).
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    {
        echo 'export ANDROID_HOME=/home/user/android-sdk'
        echo 'export ANDROID_SDK_ROOT=/home/user/android-sdk'
        echo 'export PATH="$PATH:/home/user/android-sdk/platform-tools"'
    } >> "$CLAUDE_ENV_FILE"
fi
