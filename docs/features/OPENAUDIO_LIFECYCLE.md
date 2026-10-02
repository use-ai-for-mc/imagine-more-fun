# OpenAudioMC WebView lifecycle

> Current design contract. Recheck implementation and tests when changing lifecycle semantics.

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
- Native shutdown pauses media, removes media sources, closes still-live `AudioContext` instances,
  detaches delegates and script handlers, removes the `WKWebView`, and closes its window. The
  page-side context list uses `WeakRef`; it never keeps detached Web Audio graphs alive merely so
  they can be resumed or closed later.
- Java waits for graceful helper exit, then terminates only the exact helper PID and descendant
  handles captured from it. It never searches for or kills WebKit processes by name.
- If Minecraft exits without its normal lifecycle callback, closing the parent pipe produces EOF in
  the helper and triggers the same native cleanup path.

## Detached media guard

OpenAudioMC can receive an expired pickup immediately before the current ride media. Its
`MediaTrack.applyStartDateIfAny()` calls `stop()`, which removes the non-looping track from the
channel, but the surrounding async `play()` path can still proceed to `audio.play()`. If WebKit's
play promise is delayed, that element becomes audible after channel removal and no longer responds
to channel or master-volume updates. A second, current media command then produces the audible
duplicate.

The production `legacy-guarded` mode keeps the stable legacy volume path and adds only an
element-scoped pre-native guard. A no-op `pause()` on a detached, URL-backed audio element at time
zero records a 100 ms candidate; it does not dispose anything by itself. If that same element then
attempts its very first native `play()` while it still has an OpenAudioMC `ended` owner, the helper
rejects the attempt with `AbortError` before calling WebKit, removes its terminal listeners,
disconnects its media-source graph, clears `src`/`srcObject`, and calls `load()`.

The decision is never URL-, ride-, or server-media-ID-scoped. A second element with the same source
is therefore allowed, as are attached page media, video, `srcObject` voice media, expired
candidates, native-play retries, and pause/resume after a successful first play. Guard telemetry
exposes only counters, source hashes, and pause age; it never exposes signed media URLs.

`legacy-minimum` remains the emergency control path, `legacy-observe` remains a non-mutating
evidence mode, and the broader `managed-lifecycle` injection remains available for offline tests.

## Connection intent and server rejoin

The current helper/session state is separate from the user's connection intent. Enabling automatic
OpenAudioMC or using `/oa connect` makes that intent true for the rest of the Minecraft process.
A multiplayer disconnect always destroys the old helper and invalidates any cold helper startup,
but it preserves the intent. The next ImagineFun `JOIN` can therefore accept the server's normal
session offer even when a separate reconnect mod performed the network reconnect. `/oa disconnect`
is the explicit opt-out; it clears the intent, while a full Minecraft restart returns to the saved
configuration.

The server's automatic offer gets a 12-second head start on every eligible `JOIN`. If it does not
arrive, IMF sends `/audio`. Each command request has a generation-scoped 10-second timeout: an
accepted URL cancels that timeout and any delayed fallback, while a missing URL enters the existing
bounded exponential recovery sequence. A stale timeout cannot expire a newer request, and duplicate
offers cannot launch a second cold helper.

## Release logging

Session URLs are bearer material. Connection logs therefore record only the fixed OpenAudioMC host
and whether the URL uses a path, query, or fragment; native load/error responses never echo the
address. Routine GPU and media-health samples remain active for threshold decisions but log only at
DEBUG. INFO/WARN is reserved for connection state changes, telemetry availability transitions,
threshold crossings, and helper recovery/recycle actions.

## MonkeyCraft native audio handoff

The optional Monkeycraft integration listens for an `openaudiomc` INFO_PACKET with a boolean
`active`. While true, `OpenAudioMcService` invalidates pending startup, closes the desktop helper,
and suppresses connect offers, server-ended recovery, and delayed auto-connect tasks. It preserves
the user's connection intent and volume. False schedules a fresh desktop session only when that
intent is still enabled; a user who had desktop audio off remains off.

The mobile app reports ownership before navigation and releases it after teardown, including
failed connection or refresh. A Monkeycraft transport disconnect does not automatically release:
the mobile WebView can continue playing in the background. The existing explicit desktop connect
action clears the process-local ownership latch when manual recovery is needed. Desktop process
restart is not a durable ownership protocol and still needs a cross-device acceptance case.
External browser audio does not report this native ownership field.

Local 2026-09-21 validation used real Minecraft and an iOS diagnostic simulator: one minute of
mobile ownership, including a controlled transport reconnect, did not restart the desktop helper;
explicit mobile disconnect restored the previous desired connection and unchanged volume. Startup
JUnit cases cover suppression and keeping a previously disabled desktop session off. This does not
establish physical-device audibility, normal mobile GUI, or process-crash/restart acceptance.

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
