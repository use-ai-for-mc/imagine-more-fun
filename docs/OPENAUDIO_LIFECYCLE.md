# OpenAudioMC WebView lifecycle

IMF currently runs the OpenAudioMC web client in a native helper because the service combines its
session protocol, Web Audio graph, AudioWorklets, media elements, and browser-side spatial audio.
The macOS implementation therefore still uses `WKWebView`, but a logical audio session no longer
shares a helper or WebView with any later session.

## Resource boundary

- Each session has a UUID and launches one helper process with a non-persistent website data store.
- The monitor has only one JavaScript evaluation in flight. Failures retry after 3 seconds and 6
  seconds; the third consecutive failure stops the session and helper.
- Server end, helper EOF, session drop, reconnect, manual disconnect, server leave, and Minecraft
  shutdown cancel the monitor and related scheduled work before closing the helper.
- Native shutdown pauses media, removes media sources, closes tracked `AudioContext` instances,
  detaches delegates and script handlers, removes the `WKWebView`, and closes its window.
- Java waits for graceful helper exit, then terminates only the exact helper PID and descendant
  handles captured from it. It never searches for or kills WebKit processes by name.
- If Minecraft exits without its normal lifecycle callback, closing the parent pipe produces EOF in
  the helper and triggers the same native cleanup path.

## Longer-term migration

Pure audio should eventually stop depending on a browser engine. A safe migration requires first
documenting the OpenAudioMC session/socket protocol and the spatial playlist/worklet behavior, then
replacing them with a protocol-native client and a bounded native audio graph. On macOS,
`AVAudioEngine` is a plausible playback target; a cross-platform implementation could use an
existing Minecraft/OpenAL integration. The migration should preserve signed-session handling,
positional updates, volume semantics, reconnect behavior, and server compatibility tests before the
WebView path is removed.

Until that migration is complete, process-per-session isolation is the fail-safe: a wedged WebKit
media/GPU process cannot be reused by the next IMF audio session.
