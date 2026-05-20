#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}"
TARGET_DIR="/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/mods/"

# Derive the jar name from gradle.properties so a mod_version bump
# doesn't silently leave us deploying a stale jar.
MOD_VERSION=$(grep '^mod_version=' "${PROJECT_DIR}/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
if [ -z "${MOD_VERSION}" ]; then
    echo "Error: could not read mod_version from ${PROJECT_DIR}/gradle.properties"
    exit 1
fi
JAR_NAME="imaginemorefun-${MOD_VERSION}.jar"
SOURCE_JAR="${PROJECT_DIR}/build/libs/${JAR_NAME}"
TARGET_JAR="${TARGET_DIR}/${JAR_NAME}"

# After first run, ImfMigration moves the native helpers here.
# PrismLauncher (current launcher) and ModrinthApp (older launcher) use
# different game-dir roots; clear both so a rebuilt helper is re-extracted from
# the JAR on next launch regardless of which one the user runs.
PRISM_NATIVE_CACHE_DIR="/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/config/imaginemorefun/native"
NATIVE_CACHE_DIR="/Users/cusgadmin/Library/Application Support/ModrinthApp/profiles/ImagineFun/config/imaginemorefun/native"
# Legacy NRA location — ImfMigration.runOnce() will have moved anything useful
# away from here on first launch, but we clear it too in case a prior run left
# cached binaries behind.
LEGACY_NATIVE_CACHE_DIR="/Users/cusgadmin/Library/Application Support/ModrinthApp/profiles/ImagineFun/config/not-riding-alert/native"

# Rebuild native helper binaries (must happen before gradlew build so the JAR includes them).
# macOS helpers are built here; Windows is best-effort (skipped if dotnet is missing) — the
# standalone native/build-all.sh remains the source of truth for CI Windows builds.
echo "Rebuilding macOS native WebView helper..."
swiftc -O \
    -o "${PROJECT_DIR}/src/main/resources/native/macos/webview-helper" \
    "${PROJECT_DIR}/native/macos/WebViewHelper.swift" \
    -framework WebKit -framework AppKit

echo "Rebuilding macOS native Status helper..."
swiftc -O \
    -o "${PROJECT_DIR}/src/main/resources/native/macos/status-helper" \
    "${PROJECT_DIR}/native/macos/StatusHelper.swift" \
    -framework AppKit

if command -v dotnet &>/dev/null; then
    echo "Rebuilding Windows native Status helper (dotnet cross-compile)..."
    (
        cd "${PROJECT_DIR}/native/windows/status"
        dotnet publish StatusHelper.csproj -c Release -r win-x64 --no-self-contained \
            -p:PublishSingleFile=true -o publish 2>&1 | grep -v "warning MSB3277" || true
    )
    STATUS_EXE="${PROJECT_DIR}/native/windows/status/publish/status-helper.exe"
    if [ -f "${STATUS_EXE}" ]; then
        cp "${STATUS_EXE}" "${PROJECT_DIR}/src/main/resources/native/windows/status-helper.exe"
        echo "Copied status-helper.exe into resources."
    else
        echo "WARNING: status-helper.exe not produced; skipping."
    fi
else
    echo "dotnet not found; skipping Windows Status helper build."
fi

echo "Building ImagineMoreFun mod..."
cd "${PROJECT_DIR}"
./gradlew spotlessApply
./gradlew build

if [ ! -f "${SOURCE_JAR}" ]; then
    echo "Error: Build artifact not found at ${SOURCE_JAR}"
    exit 1
fi

# Clear cached native binaries so the updated ones from the JAR get extracted on next launch.
for dir in "${PRISM_NATIVE_CACHE_DIR}" "${NATIVE_CACHE_DIR}" "${LEGACY_NATIVE_CACHE_DIR}"; do
    if [ -d "${dir}" ]; then
        echo "Clearing cached native binaries: ${dir}"
        rm -rf "${dir}"
    fi
done

echo "Creating target directory if it doesn't exist..."
mkdir -p "${TARGET_DIR}"

# Remove the three old independent jars that this merged mod replaces.
# Their code now lives inside imaginemorefun-*.jar; keeping both loaded would
# cause mixins and entrypoints to run twice.
remove_old_jar() {
    local pattern="$1"
    # NUL-separate find output so paths with spaces (TARGET_DIR contains
    # "Application Support") survive the round-trip — the previous
    # `xargs rm -f` silently failed because xargs split on whitespace.
    local found_count
    found_count=$(find "${TARGET_DIR}" -maxdepth 1 -type f -name "${pattern}" 2>/dev/null | wc -l | tr -d '[:space:]')
    if [ "${found_count}" != "0" ]; then
        echo "Removing superseded jar(s) matching ${pattern}:"
        find "${TARGET_DIR}" -maxdepth 1 -type f -name "${pattern}" -print 2>/dev/null | sed 's|^|  - |'
        find "${TARGET_DIR}" -maxdepth 1 -type f -name "${pattern}" -delete 2>/dev/null
    fi
}

remove_old_jar "not-riding-alert-*.jar"
remove_old_jar "pim-*.jar"
remove_old_jar "pim2-*.jar"
remove_old_jar "skin-cache-mod-*.jar"

# Wipe any prior imaginemorefun-*.jar EXCEPT the current-version filename, so
# a prior mod_version doesn't sit alongside the new one (Fabric refuses to
# start with duplicate mod IDs) and stray -sources jars don't pollute mods/.
# The current-version jar is intentionally left in place — the atomic
# `mv -f ${TEMP_JAR} ${TARGET_JAR}` below replaces it via rename(2), which
# preserves the old inode for any running JVM's open ZipFile handle. Deleting
# it here first would leave a brief window where the target path doesn't
# exist, breaking a freshly-launching JVM that opens the file in that window.
remove_old_jar_except_current() {
    local pattern="$1"
    local keep="$2"
    local found_count
    found_count=$(find "${TARGET_DIR}" -maxdepth 1 -type f -name "${pattern}" -not -name "${keep}" 2>/dev/null | wc -l | tr -d '[:space:]')
    if [ "${found_count}" != "0" ]; then
        echo "Removing superseded jar(s) matching ${pattern} (keeping ${keep}):"
        find "${TARGET_DIR}" -maxdepth 1 -type f -name "${pattern}" -not -name "${keep}" -print 2>/dev/null | sed 's|^|  - |'
        find "${TARGET_DIR}" -maxdepth 1 -type f -name "${pattern}" -not -name "${keep}" -delete 2>/dev/null
    fi
}

remove_old_jar_except_current "imaginemorefun-*.jar" "${JAR_NAME}"

verify_jar() {
    local jar_file="$1"
    unzip -tq "${jar_file}" 2>/dev/null
}

# Write to a temp file in the SAME directory as the target, then atomically
# rename it over the target. POSIX rename(2) is atomic and preserves the old
# inode for any already-running JVM (whose open ZipFile handle holds cached
# offsets into the old central directory), while new launches pick up the new
# inode. An in-place truncate-and-rewrite corrupts the file from the running
# JVM's perspective and triggers "invalid zip signature" crashes.
#
# The temp file MUST live on the same filesystem/volume as the destination,
# otherwise `mv` silently falls back to a non-atomic copy+unlink. Using the
# target directory guarantees this on Linux and macOS (APFS/HFS+).
#
# On Windows this pattern does not work: the OS refuses to rename over a file
# that another process has open. That case needs versioned filenames or a
# pre-launch swap hook — out of scope for this macOS deploy script.
TEMP_JAR="${TARGET_JAR}.new"

MAX_RETRIES=3
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    echo "Copying jar to ${TEMP_JAR}..."
    cp -f "${SOURCE_JAR}" "${TEMP_JAR}"

    echo "Verifying copied jar integrity..."
    if verify_jar "${TEMP_JAR}"; then
        echo "Jar verification successful! Atomically swapping into place..."
        mv -f "${TEMP_JAR}" "${TARGET_JAR}"
        break
    else
        RETRY_COUNT=$((RETRY_COUNT + 1))
        rm -f "${TEMP_JAR}"
        if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
            echo "Warning: Jar verification failed (attempt $RETRY_COUNT/$MAX_RETRIES). Retrying..."
            sleep 1
        else
            echo "Error: Failed to copy a valid jar after $MAX_RETRIES attempts"
            echo "Source: ${SOURCE_JAR}"
            echo "Target: ${TARGET_JAR}"
            exit 1
        fi
    fi
done

echo "Build and deployment complete!"
echo "Jar copied to: ${TARGET_JAR}"
