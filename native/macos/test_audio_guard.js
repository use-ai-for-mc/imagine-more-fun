#!/usr/bin/env node

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const helperSource = fs.readFileSync(path.join(__dirname, "WebViewHelper.swift"), "utf8");

function injectedScript(name) {
  const pattern = new RegExp(
    `let ${name} = WKUserScript\\(source: """\\n([\\s\\S]*?)\\n\\s*""", injectionTime`,
  );
  const match = helperSource.match(pattern);
  assert.ok(match, `could not extract ${name}`);
  return match[1];
}

const allMedia = new Set();
global.window = global;
global.document = {
  contains(media) {
    return media.attached;
  },
  querySelectorAll() {
    return Array.from(allMedia).filter((media) => media.attached);
  },
};
window.webkit = {
  messageHandlers: {
    nativeLog: { postMessage() {} },
  },
};

class FakeMediaElement {
  constructor(tagName = "AUDIO") {
    this.tagName = tagName;
    this.attached = false;
    this.currentSrc = "";
    this.currentTime = 0;
    this.duration = 120;
    this.paused = true;
    this.ended = false;
    this.readyState = 4;
    this.networkState = 1;
    this.srcObject = null;
    this.nativePlayCalls = 0;
    this.nativePauseCalls = 0;
    this.loadCalls = 0;
    this.listeners = new Map();
    this._src = "";
    this._volume = 1;
    this._muted = false;
    allMedia.add(this);
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || new Set();
    listeners.add(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type, listener) {
    const listeners = this.listeners.get(type);
    if (listeners) listeners.delete(listener);
  }

  dispatchEvent(event) {
    const type = typeof event === "string" ? event : event.type;
    for (const listener of Array.from(this.listeners.get(type) || [])) {
      if (typeof listener === "function") listener.call(this, { type, target: this });
      else if (listener && typeof listener.handleEvent === "function") {
        listener.handleEvent({ type, target: this });
      }
    }
  }

  listenerCount() {
    let count = 0;
    for (const listeners of this.listeners.values()) count += listeners.size;
    return count;
  }

  play() {
    this.nativePlayCalls += 1;
    this.paused = false;
    return Promise.resolve();
  }

  pause() {
    this.nativePauseCalls += 1;
    this.paused = true;
  }

  load() {
    this.loadCalls += 1;
    this.paused = true;
  }

  removeAttribute(name) {
    if (name === "src") {
      this._src = "";
      this.currentSrc = "";
    }
  }

  getAttribute(name) {
    return name === "src" ? this._src : null;
  }

  get src() {
    return this._src;
  }

  set src(value) {
    this._src = value;
    this.currentSrc = value;
  }

  get volume() {
    return this._volume;
  }

  set volume(value) {
    this._volume = value;
  }

  get muted() {
    return this._muted;
  }

  set muted(value) {
    this._muted = value;
  }
}

class FakeAudioNode {
  constructor() {
    this.connectCalls = 0;
    this.disconnectCalls = 0;
    this.connections = new Set();
  }

  connect(destination) {
    this.connectCalls += 1;
    this.connections.add(destination);
    return destination;
  }

  disconnect(destination) {
    this.disconnectCalls += 1;
    if (destination) this.connections.delete(destination);
    else this.connections.clear();
  }
}

class FakeAudioContext {
  constructor() {
    this.state = "running";
    this.closed = false;
  }

  createMediaElementSource() {
    return new FakeAudioNode();
  }

  createGain() {
    const node = new FakeAudioNode();
    node.gain = { value: 1 };
    this.lastGain = node;
    return node;
  }

  resume() {
    this.state = "running";
    return Promise.resolve();
  }

  close() {
    this.closed = true;
    this.state = "closed";
    return Promise.resolve();
  }
}

class FakeWebSocket {
  constructor() {
    this.listeners = new Map();
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  emit(type, event = {}) {
    for (const listener of this.listeners.get(type) || []) listener.call(this, event);
  }
}

class FakeXMLHttpRequest {
  constructor() {
    this.listeners = new Map();
    this.responseURL = "";
    this.responseText = "";
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  send() {}
}

global.HTMLMediaElement = FakeMediaElement;
global.AudioContext = FakeAudioContext;
global.webkitAudioContext = FakeAudioContext;
global.WebSocket = FakeWebSocket;
global.XMLHttpRequest = FakeXMLHttpRequest;

// Match WKUserScript installation order.
eval(injectedScript("audioPolyfill"));
eval(injectedScript("audioVolumeFix"));
eval(injectedScript("staleMediaPlayGuard"));

function owner(media) {
  const listener = () => {};
  media.addEventListener("ended", listener);
  return listener;
}

function destroyPacket(all = false, id = "id") {
  return `42["data",{"type":"x.ClientDestroyMediaPayload","payload":{"soundId":"${id}","all":${all}}}]`;
}

function createPacket(id, source, options = {}) {
  return `42["data",{"type":"x.ClientCreateMediaPayload","payload":{"media":${JSON.stringify({ mediaId: id, source, ...options })}}}]`;
}

function updatePacket(id, volume) {
  return `42["data",{"type":"x.ClientUpdateMediaPayload","payload":{"mediaOptions":{"target":"${id}","volume":${volume},"fadeTimeMs":0}}}]`;
}

async function flushLifecycle() {
  await Promise.resolve();
  await Promise.resolve();
}

function assertDisposed(media, message) {
  assert.equal(media.src, "", `${message}: src cleared`);
  assert.equal(media.srcObject, null, `${message}: srcObject cleared`);
  assert.equal(media.listenerCount(), 0, `${message}: listeners removed`);
  assert.ok(media.loadCalls >= 1, `${message}: load called`);
  assert.equal(media.paused, true, `${message}: paused`);
  assert.equal(media.onended, null, `${message}: event handler properties cleared`);
}

async function main() {
  const legacyMinimumVolumeFix = injectedScript("legacyMinimumVolumeFix");
  const legacyMinimumHealth = injectedScript("legacyMinimumHealth");
  assert.match(
    helperSource,
    /default:[\s\S]*?return \.legacyGuarded/,
    "protected legacy build defaults to legacy-guarded injection",
  );
  assert.match(
    helperSource,
    /case "legacy", "legacy-minimum", "minimum":[\s\S]*?return \.legacyMinimum/,
    "legacy-minimum remains available as an explicit control mode",
  );
  assert.doesNotMatch(
    legacyMinimumVolumeFix,
    /HTMLMediaElement\.prototype\.(play|pause|addEventListener|removeEventListener)|window\.WebSocket|liveMedia|new Set\(/,
    "legacy-minimum must not install deep media lifecycle or relay hooks",
  );
  assert.match(legacyMinimumHealth, /mode: 'legacy-minimum'/);
  assert.match(
    helperSource,
    /if audioDiagnosticsEnabled \{\s*config\.userContentController\.addUserScript\(audioDiagnostics\)/,
    "temporary diagnostics must be opt-in",
  );

  window.__nra_preferred_volume = 0;
  window.__nra_master_volume_percent = 0;
  window.__nra_volume_gate_active = true;
  const startupGateMedia = new HTMLMediaElement("AUDIO");
  startupGateMedia.src = "https://media.invalid/startup-gate.ogg";
  owner(startupGateMedia);
  const startupGateContext = new AudioContext();
  startupGateContext.createMediaElementSource(startupGateMedia);
  await startupGateMedia.play();
  assert.equal(startupGateMedia.muted, true, "startup gate mutes media before native play");
  assert.equal(startupGateContext.lastGain.gain.value, 0, "startup gate silences spatial gain");
  window.__nra_commit_master_volume(0);
  assert.equal(startupGateMedia.muted, true, "committing persisted zero keeps media muted");
  assert.equal(startupGateContext.lastGain.gain.value, 0, "persisted zero keeps spatial gain silent");
  window.__nra_commit_master_volume(35);
  assert.equal(startupGateMedia.muted, false, "positive master volume releases forced mute");
  assert.equal(startupGateContext.lastGain.gain.value, 1, "positive volume restores media gain");
  window.__nra_dispose_media(startupGateMedia, "startup-gate-test");
  await startupGateContext.close();
  const audioContextBaseline = window.__nra_audio_context_health().live;

  const snapshotMedia = Array.from({ length: 40 }, (_, index) => {
    const media = new HTMLMediaElement("AUDIO");
    media.src = `https://media.invalid/private-${index}.mp3?signature=must-not-leak`;
    owner(media);
    return media;
  });
  await snapshotMedia[0].play();
  const boundedSnapshot = window.__nra_media_snapshot(1000);
  assert.equal(boundedSnapshot.length, 32, "GPU pressure media snapshot is capped at 32");
  assert.equal(boundedSnapshot[0].playing, true, "playing media is prioritized in snapshot");
  assert.match(boundedSnapshot[0].sourceHash, /^\d+:[0-9a-f]+$/);
  assert.doesNotMatch(
    JSON.stringify(boundedSnapshot),
    /media\.invalid|signature|must-not-leak/,
    "GPU pressure snapshot never contains raw or signed media URLs",
  );
  window.__nra_dispose_all_media("snapshot-test");
  snapshotMedia.forEach((media) => assertDisposed(media, "snapshot cleanup"));

  const normalEnded = new HTMLMediaElement("AUDIO");
  normalEnded.src = "https://media.invalid/ended.ogg";
  normalEnded.onended = () => {};
  owner(normalEnded);
  await normalEnded.play();
  normalEnded.dispatchEvent("ended");
  await flushLifecycle();
  assertDisposed(normalEnded, "normal ended");
  const disposedAfterEnded = window.__nra_media_health().disposed;
  assert.equal(window.__nra_dispose_media(normalEnded, "duplicate"), false, "dispose is idempotent");
  assert.equal(window.__nra_media_health().disposed, disposedAfterEnded);

  const socket = new WebSocket("wss://relay.invalid");

  // Reproduce the field failure: a session starts at master volume 0, so OAM writes the
  // element itself to volume 0. Raising only the global master must reconstruct the channel
  // volume and clear the helper's forced mute for already-playing media.
  window.__nra_commit_master_volume(0);
  const zeroStarted = new HTMLMediaElement("AUDIO");
  const zeroStartedSource = "https://media.invalid/zero-started.ogg";
  socket.emit("message", { data: createPacket("zero-started", zeroStartedSource, { volume: 60 }) });
  zeroStarted.src = zeroStartedSource;
  owner(zeroStarted);
  zeroStarted.volume = 0;
  await zeroStarted.play();
  assert.equal(zeroStarted.muted, true, "master zero starts known server media muted");
  assert.ok(
    window.__nra_media_health().playingSilentLive >= 1,
    "health identifies playing media that is effectively silent",
  );
  const restoresBeforeMasterChange = window.__nra_media_health().volumeRestored;
  window.__nra_commit_master_volume(35);
  assert.equal(zeroStarted.volume, 0.21, "0 to 35 restores channel times master volume");
  assert.equal(zeroStarted.muted, false, "0 to 35 clears the forced media mute");
  assert.equal(
    window.__nra_media_health().volumeRestored,
    restoresBeforeMasterChange + 1,
    "master transition records the element-level recovery",
  );
  window.__nra_dispose_media(zeroStarted, "zero-started-test");

  // A new ride cue can also arrive while OAM still has a stale zero internally. The play
  // boundary must restore the latest channel value, including a server update before play.
  const lateZero = new HTMLMediaElement("AUDIO");
  const lateZeroSource = "https://media.invalid/late-zero.ogg";
  socket.emit("message", { data: createPacket("late-zero", lateZeroSource, { volume: 80 }) });
  lateZero.src = lateZeroSource;
  owner(lateZero);
  socket.emit("message", { data: updatePacket("late-zero", 40) });
  lateZero.volume = 0;
  await lateZero.play();
  assert.equal(lateZero.volume, 0.14, "pre-play recovery uses the latest server channel volume");
  assert.equal(lateZero.muted, false, "new ride cue is unmuted before native play");
  lateZero.volume = 0;
  window.__nra_commit_master_volume(35);
  assert.equal(lateZero.volume, 0, "unchanged health poll does not override a later channel fade");
  window.__nra_dispose_media(lateZero, "late-zero-test");

  const translatedSource = new HTMLMediaElement("AUDIO");
  socket.emit("message", {
    data: createPacket("translated", "files:rides/translated-cue.ogg", { volume: 50 }),
  });
  translatedSource.src =
    "https://usercontent.openaudiomc.net/uploads/bucket/rides/translated-cue.ogg";
  owner(translatedSource);
  translatedSource.volume = 0;
  window.__nra_commit_master_volume(0);
  await translatedSource.play();
  window.__nra_commit_master_volume(35);
  assert.equal(
    translatedSource.volume,
    0.175,
    "translated files source retains server channel ownership for volume recovery",
  );
  socket.emit("message", { data: destroyPacket(false, "translated") });
  await flushLifecycle();
  assertDisposed(translatedSource, "translated source server destroy");

  const serverDestroyed = new HTMLMediaElement("AUDIO");
  const serverDestroyedSource = "https://media.invalid/server-destroy.ogg";
  const serverCreatePacket = createPacket("id", serverDestroyedSource, {
    muteSpeakers: true,
    muteRegions: true,
  });
  const parsedServerCreatePacket = JSON.parse(serverCreatePacket.slice(2));
  assert.equal(
    parsedServerCreatePacket[1].payload.media.muteSpeakers,
    false,
    "OpenAudioMC sees muteSpeakers disabled",
  );
  assert.equal(
    parsedServerCreatePacket[1].payload.media.muteRegions,
    true,
    "muteRegions remains unchanged",
  );
  assert.equal(
    window.__nra_media_health().muteSpeakersSuppressed,
    1,
    "speaker-mute suppression is counted",
  );
  assert.ok(
    window.__nra_media_health().lastMuteSpeakersSuppressedAt > 0,
    "speaker-mute suppression timestamp is recorded",
  );
  socket.emit("message", {
    data: serverCreatePacket,
  });
  serverDestroyed.src = serverDestroyedSource;
  assert.equal(
    window.__nra_media_health().muteSpeakersLive,
    0,
    "suppressed muteSpeakers policy is not associated with media",
  );
  assert.equal(
    window.__nra_media_health().muteRegionsLive,
    1,
    "unchanged muteRegions policy remains associated with media",
  );
  owner(serverDestroyed);
  await serverDestroyed.play();
  socket.emit("message", { data: destroyPacket(false) });
  await flushLifecycle();
  assertDisposed(serverDestroyed, "server destroy");
  assert.ok(window.__nra_media_health().disposeReasons["server-destroy"] >= 1);
  assert.equal(
    window.__nra_media_health().muteSpeakersLive,
    0,
    "disposed muting media leaves no live muteSpeakers policy",
  );
  assert.equal(
    window.__nra_media_health().muteRegionsLive,
    0,
    "disposed media leaves no live muteRegions policy",
  );

  const destroyAllMedia = Array.from({ length: 3 }, (_, index) => {
    const media = new HTMLMediaElement("AUDIO");
    media.src = `https://media.invalid/destroy-all-${index}.ogg`;
    owner(media);
    return media;
  });
  await Promise.all(destroyAllMedia.map((media) => media.play()));
  socket.emit("message", { data: destroyPacket(true) });
  const postDestroyAllMedia = new HTMLMediaElement("AUDIO");
  postDestroyAllMedia.src = "https://media.invalid/new-ride-cue-after-destroy-all.ogg";
  owner(postDestroyAllMedia);
  await postDestroyAllMedia.play();
  await flushLifecycle();
  destroyAllMedia.forEach((media) => assertDisposed(media, "server destroy-all"));
  assert.notEqual(
    postDestroyAllMedia.src,
    "",
    "media created after destroy-all receipt survives the old-media teardown",
  );
  assert.equal(postDestroyAllMedia.paused, false, "new ride cue remains playing");
  window.__nra_dispose_media(postDestroyAllMedia, "post-destroy-all-test-cleanup");
  assertDisposed(postDestroyAllMedia, "post destroy-all cleanup");

  const expiredPickup = new HTMLMediaElement("AUDIO");
  expiredPickup.src = "https://media.invalid/expired-pickup.ogg";
  owner(expiredPickup);
  expiredPickup.pause();
  await flushLifecycle();
  assertDisposed(expiredPickup, "expired pickup");
  await assert.rejects(expiredPickup.play(), { name: "AbortError" });
  assert.equal(expiredPickup.nativePlayCalls, 0, "expired pickup never reaches native play");

  const latePlay = new HTMLMediaElement("AUDIO");
  latePlay.src = "https://media.invalid/late.ogg";
  const lateOwner = owner(latePlay);
  await latePlay.play();
  latePlay.removeEventListener("ended", lateOwner);
  await assert.rejects(latePlay.play(), { name: "AbortError" });
  await flushLifecycle();
  assertDisposed(latePlay, "late play rejection after prior successful play");
  assert.equal(latePlay.nativePlayCalls, 1, "ownership is checked on every play");

  for (const type of ["error", "abort"]) {
    const failed = new HTMLMediaElement("AUDIO");
    failed.src = `https://media.invalid/${type}.ogg`;
    owner(failed);
    failed.dispatchEvent(type);
    await flushLifecycle();
    assertDisposed(failed, type);
  }

  const disconnected = new HTMLMediaElement("AUDIO");
  disconnected.src = "https://media.invalid/disconnect.ogg";
  owner(disconnected);
  socket.emit("close");
  await flushLifecycle();
  assertDisposed(disconnected, "relay disconnect");

  const graphed = new HTMLMediaElement("AUDIO");
  graphed.src = "https://media.invalid/spatial.ogg";
  owner(graphed);
  const context = new AudioContext();
  context.state = "suspended";
  const suspendedHealth = window.__nra_audio_context_health();
  assert.equal(
    suspendedHealth.live,
    audioContextBaseline + 1,
    "AudioContext health tracks the live context",
  );
  assert.equal(suspendedHealth.suspended, 1, "AudioContext health reports suspension");
  await window.__nra_resumeAllAudio();
  const resumedHealth = window.__nra_audio_context_health();
  assert.equal(context.state, "running", "periodic recovery resumes a suspended AudioContext");
  assert.equal(resumedHealth.running, 1, "AudioContext health reports recovery");
  assert.equal(resumedHealth.resumeAttempts, 1, "AudioContext recovery attempt is counted");
  const source = context.createMediaElementSource(graphed);
  const destination = new FakeAudioNode();
  source.connect(destination);
  assert.equal(source.connectCalls, 1, "MediaElementSource connects upstream through gain");
  assert.ok(source.connections.has(context.lastGain), "source is connected to injected gain");
  assert.ok(context.lastGain.connections.has(destination), "gain is connected to destination");
  source.disconnect();
  source.connect(destination);
  assert.equal(
    source.connectCalls,
    2,
    "MediaElementSource reconnect restores the source-to-gain edge",
  );
  assert.ok(source.connections.has(context.lastGain), "reconnected source reaches injected gain");
  assert.ok(
    context.lastGain.connections.has(destination),
    "reconnected injected gain reaches destination",
  );
  graphed.volume = 0;
  assert.equal(graphed.muted, true, "zero volume mutes spatial media");
  assert.equal(context.lastGain.gain.value, 0, "zero volume updates spatial gain");
  graphed.volume = 0.15;
  assert.equal(graphed.muted, false, "positive volume unmutes spatial media");
  assert.equal(context.lastGain.gain.value, 0.15, "positive volume updates spatial gain");
  window.__nra_dispose_media(graphed, "graph-test");
  assert.ok(source.disconnectCalls >= 1, "MediaElementSource graph disconnected");
  assertDisposed(graphed, "graph teardown");

  const beforeStress = window.__nra_media_health();
  const stressMedia = [];
  const stressPlays = [];
  for (let index = 0; index < 5000; index += 1) {
    const media = new HTMLMediaElement("AUDIO");
    media.src = `data:audio/wav;base64,stress-${index}`;
    owner(media);
    stressMedia.push(media);
    stressPlays.push(media.play());
    media.dispatchEvent("ended");
  }
  await Promise.all(stressPlays);
  await flushLifecycle();
  const afterStress = window.__nra_media_health();
  assert.equal(afterStress.created - beforeStress.created, 5000, "stress created 5000 media");
  assert.equal(afterStress.disposed - beforeStress.disposed, 5000, "stress disposed 5000 media");
  assert.equal(afterStress.live, 0, "stress live media returns to baseline");
  assert.equal(afterStress.playPending, 0, "stress leaves no pending play promises");
  stressMedia.slice(0, 3).forEach((media) => assertDisposed(media, "stress sample"));

  const shutdownMedia = new HTMLMediaElement("AUDIO");
  shutdownMedia.src = "blob:voice";
  shutdownMedia.srcObject = { stream: true };
  shutdownMedia.attached = true;
  await shutdownMedia.play();
  await window.__nra_shutdownAudio();
  assertDisposed(shutdownMedia, "session shutdown");
  assert.equal(context.closed, true, "session shutdown closes AudioContext instances");

  // Debug-only listeners are still required to unregister when the flag is enabled.
  eval(injectedScript("audioDiagnostics"));
  const diagnosticMedia = new HTMLMediaElement("AUDIO");
  diagnosticMedia.src = "https://media.invalid/debug.ogg";
  owner(diagnosticMedia);
  await diagnosticMedia.play();
  assert.ok(diagnosticMedia.listenerCount() >= 7, "debug listeners installed only in debug test");
  diagnosticMedia.dispatchEvent("ended");
  await flushLifecycle();
  assertDisposed(diagnosticMedia, "debug listener teardown");

  const finalHealth = window.__nra_media_health();
  assert.equal(finalHealth.live, 0);
  assert.equal(finalHealth.created, finalHealth.disposed);
  console.log(
    `audio lifecycle tests passed (paths=13 stress=5000 created=${finalHealth.created} disposed=${finalHealth.disposed} live=${finalHealth.live})`,
  );
  process.exit(0);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
