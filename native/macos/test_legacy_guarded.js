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

let clockMs = 1000;
global.performance = { now: () => clockMs };
global.window = global;
global.document = {
  contains(media) {
    return media.attached;
  },
};

const nativeMessages = [];
window.webkit = {
  messageHandlers: {
    nativeLog: {
      postMessage(message) {
        nativeMessages.push(message);
      },
    },
  },
};

class FakeMediaElement {
  constructor(tagName = "AUDIO") {
    this.tagName = tagName;
    this.attached = false;
    this.currentSrc = "";
    this.currentTime = 0;
    this.duration = 30;
    this.paused = true;
    this.srcObject = null;
    this.nativePlayCalls = 0;
    this.nativePauseCalls = 0;
    this.loadCalls = 0;
    this.listeners = new Map();
    this._src = "";
    this._volume = 1;
    this.playBehavior = "resolve";
    this.onended = null;
    this.onemptied = null;
    this.onerror = null;
    this.onabort = null;
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

  listenerCount() {
    let count = 0;
    for (const listeners of this.listeners.values()) count += listeners.size;
    return count;
  }

  play() {
    this.nativePlayCalls += 1;
    if (this.playBehavior === "throw") throw new Error("native play throw");
    if (this.playBehavior === "reject") {
      this.paused = true;
      return Promise.reject(new DOMException("native reject", "NotAllowedError"));
    }
    this.paused = false;
    this.nativePlayPromise = Promise.resolve("native-result");
    return this.nativePlayPromise;
  }

  pause() {
    this.nativePauseCalls += 1;
    this.paused = true;
  }

  load() {
    this.loadCalls += 1;
    this.paused = true;
    this.currentTime = 0;
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
}

class FakeAudioNode {
  constructor() {
    this.connectCalls = 0;
    this.disconnectCalls = 0;
  }

  connect(destination) {
    this.connectCalls += 1;
    return destination;
  }

  disconnect() {
    this.disconnectCalls += 1;
  }
}

class FakeAudioContext {
  createMediaElementSource() {
    const source = new FakeAudioNode();
    this.lastRawSource = source;
    return source;
  }

  createGain() {
    const gain = new FakeAudioNode();
    gain.gain = { value: 1 };
    this.lastGain = gain;
    return gain;
  }
}

global.HTMLMediaElement = FakeMediaElement;
global.AudioContext = FakeAudioContext;
global.webkitAudioContext = FakeAudioContext;

function addOwnership(media, includeAllTerminalListeners = false) {
  media.addEventListener("ended", () => {});
  if (includeAllTerminalListeners) {
    media.addEventListener("emptied", () => {});
    media.addEventListener("error", () => {});
    media.addEventListener("abort", () => {}, { capture: true });
    media.onended = () => {};
    media.onemptied = () => {};
    media.onerror = () => {};
    media.onabort = () => {};
  }
}

async function expectAbort(promise, message) {
  await assert.rejects(
    promise,
    (error) => error && error.name === "AbortError",
    message,
  );
}

async function main() {
  const legacyMinimumVolumeFix = injectedScript("legacyMinimumVolumeFix");
  const legacyGuarded = injectedScript("legacyGuarded");

  assert.match(
    helperSource,
    /case "guarded", "legacy-guarded":[\s\S]*?return \.legacyGuarded/,
  );
  assert.match(helperSource, /default:[\s\S]*?return \.legacyGuarded/);
  assert.doesNotMatch(legacyGuarded, /muteSpeakers\s*=|window\.WebSocket|server-destroy/);
  assert.doesNotMatch(legacyGuarded, /liveMedia\s*=\s*new Set/);

  eval(legacyMinimumVolumeFix);
  eval(legacyGuarded);

  assert.equal(window.__nra_audio_injection_mode, "legacy-guarded");
  assert.equal(window.__nra_media_health().mode, "legacy-guarded");

  const source = "https://secret.invalid/ariel.ogg?token=DO-NOT-LOG";
  const stale = new HTMLMediaElement();
  stale.src = source;
  addOwnership(stale, true);
  const context = new AudioContext();
  context.createMediaElementSource(stale);
  stale.pause();
  await expectAbort(stale.play(), "stale first play must be rejected before native playback");
  await Promise.resolve();
  assert.equal(stale.nativePlayCalls, 0, "stale media never reaches native play");
  assert.ok(stale.nativePauseCalls >= 2, "candidate and disposal both ensure pause");
  assert.equal(stale.src, "", "stale source is cleared");
  assert.equal(stale.listenerCount(), 0, "tracked page listeners are removed");
  assert.equal(stale.onended, null);
  assert.equal(stale.onemptied, null);
  assert.equal(stale.onerror, null);
  assert.equal(stale.onabort, null);
  assert.equal(stale.loadCalls, 1, "stale media is forced to release its resource");
  assert.ok(context.lastRawSource.disconnectCalls >= 1, "native media source is disconnected");
  assert.ok(context.lastGain.disconnectCalls >= 1, "legacy volume graph is disconnected");

  const afterStale = window.__nra_media_health();
  assert.equal(afterStale.staleCandidates, 1);
  assert.equal(afterStale.staleBlocked, 1);
  assert.equal(afterStale.staleDisposed, 1);
  assert.equal(afterStale.nativePlayForwarded, 0);
  assert.equal(afterStale.guardLiveCandidates, 0);
  assert.equal(afterStale.guardPendingTimers, 0);
  assert.match(afterStale.lastBlockedSourceHash, /^\d+:[0-9a-f]{8}$/);

  const current = new HTMLMediaElement();
  current.src = source;
  addOwnership(current);
  const currentResult = current.play();
  assert.equal(currentResult, current.nativePlayPromise, "allowed path preserves Promise identity");
  await currentResult;
  await Promise.resolve();
  assert.equal(current.nativePlayCalls, 1, "same-source replacement is allowed");
  assert.equal(current.src, source, "replacement remains loaded");

  const resumed = new HTMLMediaElement();
  resumed.src = "https://media.invalid/resume.ogg";
  addOwnership(resumed);
  await resumed.play();
  await Promise.resolve();
  resumed.pause();
  await resumed.play();
  assert.equal(resumed.nativePlayCalls, 2, "successful media can pause and resume");

  const cases = [];
  const attached = new HTMLMediaElement();
  attached.src = "https://media.invalid/attached.ogg";
  attached.attached = true;
  addOwnership(attached);
  cases.push(attached);

  const video = new HTMLMediaElement("VIDEO");
  video.src = "https://media.invalid/video.mp4";
  addOwnership(video);
  cases.push(video);

  const stream = new HTMLMediaElement();
  stream.src = "https://media.invalid/stream.ogg";
  stream.srcObject = { stream: true };
  addOwnership(stream);
  cases.push(stream);

  const unowned = new HTMLMediaElement();
  unowned.src = "https://media.invalid/unowned.ogg";
  cases.push(unowned);

  const progressed = new HTMLMediaElement();
  progressed.src = "https://media.invalid/progressed.ogg";
  progressed.currentTime = 0.5;
  addOwnership(progressed);
  cases.push(progressed);

  for (const media of cases) {
    media.pause();
    await media.play();
    assert.equal(media.nativePlayCalls, 1, `${media.src} is not a stale first-play candidate`);
  }

  const expired = new HTMLMediaElement();
  expired.src = "https://media.invalid/expired.ogg";
  addOwnership(expired);
  expired.pause();
  clockMs += 251;
  await expired.play();
  assert.equal(expired.nativePlayCalls, 1, "expired candidate is allowed");

  const sourceChanged = new HTMLMediaElement();
  sourceChanged.src = "https://media.invalid/source-a.ogg";
  addOwnership(sourceChanged);
  sourceChanged.pause();
  sourceChanged.src = "https://media.invalid/source-b.ogg";
  await sourceChanged.play();
  assert.equal(sourceChanged.nativePlayCalls, 1, "source revision change invalidates candidate");

  const rejectedRetry = new HTMLMediaElement();
  rejectedRetry.src = "https://media.invalid/retry.ogg";
  addOwnership(rejectedRetry);
  rejectedRetry.playBehavior = "reject";
  await assert.rejects(rejectedRetry.play(), (error) => error.name === "NotAllowedError");
  rejectedRetry.playBehavior = "resolve";
  await rejectedRetry.play();
  assert.equal(rejectedRetry.nativePlayCalls, 2, "a direct native rejection retry is allowed");

  const rejectedThenStopped = new HTMLMediaElement();
  rejectedThenStopped.src = "https://media.invalid/rejected-then-stopped.ogg";
  addOwnership(rejectedThenStopped);
  rejectedThenStopped.playBehavior = "reject";
  await assert.rejects(
    rejectedThenStopped.play(),
    (error) => error.name === "NotAllowedError",
  );
  rejectedThenStopped.pause();
  rejectedThenStopped.playBehavior = "resolve";
  await expectAbort(
    rejectedThenStopped.play(),
    "a failed attempt must not permanently bypass a later stale stop-before-play",
  );
  assert.equal(rejectedThenStopped.nativePlayCalls, 1);

  const beforeStress = window.__nra_media_health();
  const stress = [];
  for (let index = 0; index < 5000; index += 1) {
    const media = new HTMLMediaElement();
    media.src = `https://media.invalid/stale-${index}.ogg`;
    addOwnership(media);
    media.pause();
    stress.push(expectAbort(media.play(), `stress media ${index} must be blocked`));
  }
  await Promise.all(stress);
  await Promise.resolve();
  const afterStress = window.__nra_media_health();
  assert.equal(afterStress.staleBlocked - beforeStress.staleBlocked, 5000);
  assert.equal(afterStress.staleDisposed - beforeStress.staleDisposed, 5000);
  assert.equal(
    afterStress.nativePlayForwarded,
    beforeStress.nativePlayForwarded,
    "stress candidates never reach native play",
  );
  assert.equal(afterStress.guardLiveCandidates, 0);
  assert.equal(afterStress.guardPendingTimers, 0);

  const renderedMessages = JSON.stringify(nativeMessages);
  assert.doesNotMatch(renderedMessages, /secret\.invalid|DO-NOT-LOG|ariel\.ogg/);
  assert.match(renderedMessages, /STALE_FIRST_PLAY_BLOCKED/);

  console.log(
    `legacy-guarded tests passed (blocked=${afterStress.staleBlocked} forwarded=${afterStress.nativePlayForwarded})`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
