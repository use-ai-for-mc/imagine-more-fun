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

global.window = global;
global.document = {
  contains(media) {
    return media.attached;
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
    this.paused = true;
    this.srcObject = null;
    this.nativePlayCalls = 0;
    this.listeners = new Map();
    this._src = "";
    this._volume = 1;
    this._muted = false;
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

  play() {
    this.nativePlayCalls += 1;
    this.paused = false;
    return Promise.resolve();
  }

  pause() {
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

global.HTMLMediaElement = FakeMediaElement;
global.WebSocket = class {
  addEventListener() {}
};
global.XMLHttpRequest = class {
  addEventListener() {}
  send() {}
};

eval(injectedScript("staleMediaPlayGuard"));
eval(injectedScript("audioDiagnostics"));

async function main() {
  const track = new HTMLMediaElement("AUDIO");
  track.src = "https://media.invalid/ride.ogg";
  const pageEndedHandler = () => {};

  track.addEventListener("ended", pageEndedHandler);
  await track.play();
  assert.equal(track.nativePlayCalls, 1, "owned OpenAudioMC track should play");

  track.removeEventListener("ended", pageEndedHandler);
  await assert.rejects(
    track.play(),
    (error) => error instanceof DOMException && error.name === "AbortError",
    "destroyed track must not resume after its async load finishes",
  );
  assert.equal(
    track.nativePlayCalls,
    1,
    "diagnostic ended listener must not defeat the stale-play guard",
  );

  const expiredPickup = new HTMLMediaElement("AUDIO");
  expiredPickup.src = "https://media.invalid/expired-pickup.ogg";
  expiredPickup.addEventListener("ended", () => {});
  expiredPickup.pause();
  await assert.rejects(
    expiredPickup.play(),
    (error) => error instanceof DOMException && error.name === "AbortError",
    "track stopped before its first play must not escape its channel",
  );
  assert.equal(expiredPickup.nativePlayCalls, 0, "expired pickup must never reach native play");

  const resumedTrack = new HTMLMediaElement("AUDIO");
  resumedTrack.src = "https://media.invalid/resume.ogg";
  resumedTrack.addEventListener("ended", () => {});
  await resumedTrack.play();
  resumedTrack.pause();
  await resumedTrack.play();
  assert.equal(resumedTrack.nativePlayCalls, 2, "an owned track may pause and resume normally");

  const attachedAudio = new HTMLMediaElement("AUDIO");
  attachedAudio.attached = true;
  attachedAudio.src = "https://media.invalid/attached.ogg";
  await attachedAudio.play();
  assert.equal(attachedAudio.nativePlayCalls, 1, "attached page audio should remain supported");

  const voiceAudio = new HTMLMediaElement("AUDIO");
  voiceAudio.srcObject = {};
  voiceAudio.src = "blob:voice";
  await voiceAudio.play();
  assert.equal(voiceAudio.nativePlayCalls, 1, "WebRTC voice audio should remain supported");

  const video = new HTMLMediaElement("VIDEO");
  video.src = "https://media.invalid/keepalive.mp4";
  await video.play();
  assert.equal(video.nativePlayCalls, 1, "video playback should remain outside the audio guard");

  console.log("audio guard tests passed (7 scenarios)");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
