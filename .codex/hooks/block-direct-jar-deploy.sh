#!/usr/bin/env bash
# PreToolUse hook (Bash matcher): refuse Bash commands that would directly
# cp/mv/install an imaginemorefun-*.jar into a local ImagineFun mods/
# folder. The running Minecraft JVM has the jar open with cached
# ZipFile central-directory offsets — an in-place rewrite from a naked cp
# corrupts the zip from the JVM's view and crashes the session. The
# project-root build-and-deploy.sh handles this safely by writing a sibling
# .new file and atomic-renaming it, preserving the open inode for any
# running JVM.
#
# Exits 0 unconditionally; the deny decision is communicated via the
# hookSpecificOutput JSON written to stdout. Hooks that exit non-zero
# without proper JSON show as failures in the UI; we want a clean structured
# block instead.

set -euo pipefail

cmd=$(jq -r '.tool_input.command // ""')

# All conditions must hold for the pattern to be the dangerous one.
# Generic cp/mv elsewhere — including scp, dcp, /usr/local/bin/cp, etc. — is
# left alone by the (^|[^[:alnum:]_]) anchor.
if printf '%s' "$cmd" | grep -q 'imaginemorefun' \
   && printf '%s' "$cmd" | grep -qE '(PrismLauncher/instances/ImagineFun/.minecraft/mods|ModrinthApp/profiles/ImagineFun/mods)' \
   && printf '%s' "$cmd" | grep -qE '(^|[^[:alnum:]_])(cp|mv|install)[[:space:]]'; then
  jq -n '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: "Direct cp/mv/install of imaginemorefun jar into the ImagineFun mods folder is forbidden — a running Minecraft JVM has the file open and an in-place rewrite corrupts the zip from the running process'\''s view. Use ./build-and-deploy.sh instead, which writes a sibling .new file and atomic-rename swaps it (preserving the inode for any running JVM). The script also rebuilds native helpers, verifies the jar with unzip -tq, and cleans up stale versioned jars."
    }
  }'
fi
