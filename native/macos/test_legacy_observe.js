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

const nativeMessages = [];
global.window = global;
global.document = {
  contains(media) {
    return media.attached;
  },
};
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
  constructor() {
    this.tagName = "AUDIO";
    this.attached = false;
    this.currentSrc = "";
    this.currentTime = 0;
    this.duration = 30;
    this.paused = true;
    this.ended = false;
    this.srcObject = null;
    this._src = "";
    this._volume = 1;
    this._muted = false;
    this.listeners = new Map();
    this.nativePlayCalls = 0;
    this.nativePauseCalls = 0;
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
    const rendered = { type, target: this, currentTarget: this };
    for (const listener of Array.from(this.listeners.get(type) || [])) {
      if (typeof listener === "function") listener.call(this, rendered);
      else if (listener && typeof listener.handleEvent === "function") {
        listener.handleEvent(rendered);
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
    this.nativePlayPromise = Promise.resolve("native-result");
    return this.nativePlayPromise;
  }

  pause() {
    this.nativePauseCalls += 1;
    this.paused = true;
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
    return new FakeAudioNode();
  }

  createGain() {
    const gain = new FakeAudioNode();
    gain.gain = { value: 1 };
    return gain;
  }
}

global.HTMLMediaElement = FakeMediaElement;
global.AudioContext = FakeAudioContext;
global.webkitAudioContext = FakeAudioContext;

async function main() {
  const legacyMinimumVolumeFix = injectedScript("legacyMinimumVolumeFix");
  const legacyObserve = injectedScript("legacyObserve");

  assert.match(
    helperSource,
    /default:[\s\S]*?return \.legacyGuarded/,
    "the protected legacy build defaults to legacy-guarded",
  );
  assert.match(helperSource, /case "legacy", "legacy-minimum", "minimum":[\s\S]*?\.legacyMinimum/);
  assert.match(helperSource, /case "observe", "legacy-observe":[\s\S]*?\.legacyObserve/);
  assert.match(helperSource, /case "guarded", "legacy-guarded":[\s\S]*?\.legacyGuarded/);
  assert.doesNotMatch(
    legacyMinimumVolumeFix,
    /HTMLMediaElement\.prototype\.(play|pause|addEventListener|removeEventListener)|window\.WebSocket/,
    "legacy playback injection stays minimal",
  );
  assert.doesNotMatch(
    legacyObserve,
    /__nra_dispose_media|__nra_stale_media_play_guard|muteSpeakers\s*=|removeAttribute\(['"]src/,
    "observer cannot dispose, reject, or mutate media policy",
  );

  const nativeJSONParse = JSON.parse;
  const sentinelPacket = [
    "data",
    {
      type: "x.ClientCreateMediaPayload",
      payload: {
        media: {
          mediaId: "ariel-cue-1",
          source: "https://secret.invalid/audio.ogg?token=DO-NOT-LOG",
          loop: false,
          doPickup: true,
          volume: 0.7,
          muteSpeakers: true,
          muteRegions: false,
          startAtMillis: 125,
          startInstant: new Date(Date.now() - 2_000).toISOString(),
        },
      },
    },
  ];
  JSON.parse = function (text) {
    if (text === "SENTINEL") return sentinelPacket;
    return nativeJSONParse(text);
  };

  eval(legacyMinimumVolumeFix);
  eval(legacyObserve);

  assert.equal(window.__nra_audio_injection_mode, "legacy-observe");
  assert.equal(typeof window.__nra_dispose_media, "undefined");
  assert.equal(window.__nra_stale_media_play_guard, undefined);

  const parsedPacket = JSON.parse("SENTINEL");
  assert.equal(parsedPacket, sentinelPacket, "JSON.parse preserves native object identity");
  assert.equal(
    parsedPacket[1].payload.media.muteSpeakers,
    true,
    "observer does not alter muteSpeakers",
  );

  const media = new HTMLMediaElement();
  media.src = "https://secret.invalid/audio.ogg?token=DO-NOT-LOG";
  const pageEndedHandler = () => {};
  media.addEventListener("ended", pageEndedHandler);
  const playPromise = media.play();
  assert.equal(playPromise, media.nativePlayPromise, "play returns the original native Promise");
  await playPromise;
  await Promise.resolve();
  media.pause();
  media.dispatchEvent("ended");
  assert.equal(media.nativePlayCalls, 1);
  assert.equal(media.nativePauseCalls, 1);
  assert.equal(media.listenerCount(), 1, "terminal diagnostics remove their own listeners");

  const context = new AudioContext();
  const sourceNode = context.createMediaElementSource(media);
  const destination = new FakeAudioNode();
  assert.equal(sourceNode.connect(destination), destination, "graph connect return is preserved");
  sourceNode.disconnect();

  const beforeOverflow = window.__nra_observe_snapshot();
  for (let index = 0; index < 600; index += 1) {
    media.pause();
  }
  const snapshot = window.__nra_observe_snapshot();
  assert.equal(snapshot.capacity, 512);
  assert.equal(snapshot.count, 512, "observer ring stays bounded");
  assert.ok(snapshot.dropped >= beforeOverflow.count + 88, "observer counts overwritten events");
  assert.ok(snapshot.events.every((event) => event.kind !== "MARK"));

  const allEvidence = JSON.stringify({ snapshot, nativeMessages });
  assert.doesNotMatch(allEvidence, /secret\.invalid|DO-NOT-LOG|audio\.ogg/);
  assert.match(allEvidence, /RELAY_CREATE/);
  assert.match(allEvidence, /PLAY_CALL/);
  assert.match(allEvidence, /PLAY_RESOLVED/);
  assert.match(allEvidence, /PAUSE_CALL/);
  assert.match(allEvidence, /MEDIA_ENDED/);
  assert.match(allEvidence, /GRAPH_CREATE/);
  assert.match(allEvidence, /GRAPH_CONNECT/);
  assert.match(allEvidence, /GRAPH_DISCONNECT/);
  assert.match(
    nativeMessages[0].message,
    /epochMs=\d+/,
    "native evidence carries an exact wall-clock correlation timestamp",
  );

  const health = window.__nra_media_health();
  assert.equal(health.mode, "legacy-observe");
  assert.equal(health.created, 0, "observer counters do not impersonate managed lifecycle data");
  assert.equal(health.live, 0);
  assert.equal(health.observeEvents, 512);
  assert.equal(health.observeDropped, snapshot.dropped);
  assert.equal(health.observedMedia, 1);
  assert.equal(health.observedSourceNodes, 1);

  console.log(
    `legacy-observe tests passed (events=${snapshot.count} dropped=${snapshot.dropped} media=${health.observedMedia})`,
  );
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
