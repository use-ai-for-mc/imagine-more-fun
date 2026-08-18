# IMF OpenAudioMC `legacy-guarded` validation — 2026-08-08

> Immutable dated evidence. Hashes and deployment state below describe the 2026-08-08 artifact,
> not the current checkout or currently loaded Prism client.

## Scope

This change preserves the `legacy-minimum` playback and volume path, while adding one
element-scoped guard for the observed OpenAudioMC race:

1. A detached URL-backed `AUDIO` element with an `ended` owner is paused while already paused at
   time zero and before any successful playback.
2. The same element attempts `play()` within 100 ms without changing its source revision.
3. The helper rejects that attempt before forwarding it to WebKit, removes its terminal listeners,
   disconnects its media-source graph, clears `src`/`srcObject`, and calls `load()`.

The decision does not use ride names, source hashes, URLs, or server media IDs. A different element
with the same source is allowed. `legacy-minimum`, `legacy-observe`, and `managed-lifecycle` remain
selectable through `IMF_AUDIO_INJECTION_MODE`.

## Offline validation

The following commands passed:

```text
node native/macos/test_legacy_observe.js
node native/macos/test_legacy_guarded.js
node native/macos/test_audio_guard.js
swiftc -module-cache-path /tmp/imf-swift-module-cache -typecheck \
  native/macos/WebViewHelper.swift -framework WebKit -framework AppKit
python3 -m py_compile native/macos/test_webview_lifecycle.py
./gradlew spotlessCheck test build
cmp -s native/macos/WebViewHelper.swift \
  src/main/resources/native/macos/WebViewHelper.swift
unzip -tq build/libs/imaginemorefun-3.3.0.jar
```

JavaScript guard stress result:

```text
legacy-guarded tests passed (blocked=5002 forwarded=13)
```

The 5,000-object portion increased `staleBlocked` and `staleDisposed` by exactly 5,000, did not
increase `nativePlayForwarded`, and returned live candidate and timer counts to zero.

## Real WKWebView/CoreMedia validation

Command:

```text
python3 native/macos/test_webview_lifecycle.py --legacy-guarded-only
```

Observed results from the isolated helper and its owned WebKit GPU process:

- stale media: `AbortError`, source removed, `nativePlayForwarded` unchanged;
- same-source replacement: play resolved and `currentTime` advanced to 0.0457 seconds;
- source changed in the same task after candidate pause: allowed and resolved;
- stress totals: `staleCandidates=5002`, `staleBlocked=5001`, `staleDisposed=5001`;
- forwarded native plays stayed at 2 during all 5,000 stale stress iterations;
- `guardLiveCandidates=0`, `guardPendingTimers=0` after stress;
- WebKit GPU physical footprint: 14.2 MB before stress, 10.1 MB after stress;
- CoreMedia AudioQueue threads: 2 before and 2 after (the two intentionally allowed controls);
- CoreMedia AudioMentor threads: 2 before and 2 after;
- CoreMedia HTTP cache: 256 KB before and after;
- owned test GPU PID 33446 exited after helper shutdown.

The test does not interact with Prism or Minecraft. At final verification no Prism, Minecraft Java,
or IMF `webview-helper` process was running. Two unrelated pre-existing system WebKit GPU processes
remained and were not touched.

## Build artifact

```text
build/libs/imaginemorefun-3.3.0.jar
SHA-256 2cf32bb635ebff3f86f394c7c0c2e914e079f27af731766a97692a72c922d0c3

src/main/resources/native/macos/webview-helper
SHA-256 db3dc4c1d710bc552db2b2a887fb4f46af0b84acfbc2b7d234976b3c0525a723
```

The helper embedded in the JAR has the same SHA-256 as the tested resource binary.

## Deployment status

Atomically deployed with `./build-and-deploy.sh` to:

```text
/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/mods/imaginemorefun-3.3.0.jar
SHA-256 2cf32bb635ebff3f86f394c7c0c2e914e079f27af731766a97692a72c922d0c3
```

The previous deployed SHA-256 was
`c77e127823a9a0fedf69dea76c6f7519f92635b4cc277b3167d7116e5d193ff9`. The post-deploy target
hash matches the build artifact, the JAR passes archive verification, no `.new` staging file
remains, and the cached Prism native helper directory was removed so the new helper will be
extracted on the next launch.

No Prism, Minecraft Java, or IMF `webview-helper` process was running during deployment. No client
was started, restarted, reconnected, or terminated.
