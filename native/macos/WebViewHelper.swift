// WebViewHelper.swift
// macOS native helper for OpenAudioMC headless browser integration.
//
// Creates a hidden NSWindow with WKWebView, reads JSON commands from stdin,
// executes them, and writes JSON responses to stdout. Audio plays through the
// system mixer via WebKit's built-in audio engine.
//
// Build:
//   swiftc -O -o webview-helper WebViewHelper.swift -framework WebKit -framework AppKit
//
// Protocol (newline-delimited JSON):
//   Commands (stdin):
//     {"cmd":"load","url":"https://..."}
//     {"cmd":"eval","js":"...","id":"uuid"}
//     {"cmd":"quit"}
//   Responses (stdout):
//     {"type":"ready"}
//     {"type":"loaded","url":"...","success":true}
//     {"type":"eval_result","id":"uuid","result":{...}}
//     {"type":"console","level":"log|warn|error","message":"..."}
//     {"type":"error","message":"..."}

import AppKit
import Foundation
import WebKit

// MARK: - JSON helpers

func jsonLine(_ dict: [String: Any]) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: dict, options: []),
          let str = String(data: data, encoding: .utf8) else {
        return "{\"type\":\"error\",\"message\":\"json serialization failed\"}"
    }
    return str
}

func writeLine(_ line: String) {
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
}

// MARK: - Console message handler

class ConsoleMessageHandler: NSObject, WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: String],
              let level = body["level"],
              let msg = body["message"] else { return }
        writeLine(jsonLine(["type": "console", "level": level, "message": String(msg.prefix(1000))]))
    }
}

// MARK: - WKWebView wrapper

class WebViewManager: NSObject, WKNavigationDelegate, WKUIDelegate {
    let webView: WKWebView
    let window: NSWindow
    let consoleHandler = ConsoleMessageHandler()
    private var destroyed = false
    private var shutdownCompletion: (() -> Void)?

    override init() {
        let config = WKWebViewConfiguration()
        // Java launches one helper process per IMF session, which is the effective isolation
        // boundary on modern macOS (custom WKProcessPool instances have had no effect since 12.0).
        config.websiteDataStore = .nonPersistent()
        config.mediaTypesRequiringUserActionForPlayback = []  // No user gesture needed
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.isFraudulentWebsiteWarningEnabled = false

        // Register message handler for console forwarding
        config.userContentController.add(consoleHandler, name: "nativeLog")

        // Inject WebRTC polyfill BEFORE page scripts run.
        // OpenAudioMC checks for RTCPeerConnection; WKWebView may not expose it.
        // The actual audio uses Web Audio API / HTTP streaming, not WebRTC, so a
        // stub is sufficient to get past the browser-support check.
        let webrtcPolyfill = WKUserScript(source: """
            (function() {
                if (!window.RTCPeerConnection) {
                    window.RTCPeerConnection = function(config) {
                        this.localDescription = null;
                        this.remoteDescription = null;
                        this.signalingState = 'stable';
                        this.iceConnectionState = 'new';
                        this.connectionState = 'new';
                        this.onicecandidate = null;
                        this.ontrack = null;
                        this.onconnectionstatechange = null;
                        this.oniceconnectionstatechange = null;
                    };
                    RTCPeerConnection.prototype.createOffer = function() { return Promise.resolve({}); };
                    RTCPeerConnection.prototype.createAnswer = function() { return Promise.resolve({}); };
                    RTCPeerConnection.prototype.setLocalDescription = function(d) { this.localDescription = d; return Promise.resolve(); };
                    RTCPeerConnection.prototype.setRemoteDescription = function(d) { this.remoteDescription = d; return Promise.resolve(); };
                    RTCPeerConnection.prototype.addIceCandidate = function() { return Promise.resolve(); };
                    RTCPeerConnection.prototype.addTrack = function() { return {}; };
                    RTCPeerConnection.prototype.removeTrack = function() {};
                    RTCPeerConnection.prototype.close = function() {};
                    RTCPeerConnection.prototype.getStats = function() { return Promise.resolve([]); };
                    RTCPeerConnection.prototype.getSenders = function() { return []; };
                    RTCPeerConnection.prototype.getReceivers = function() { return []; };
                    RTCPeerConnection.prototype.addEventListener = function() {};
                    RTCPeerConnection.prototype.removeEventListener = function() {};
                    window.webkitRTCPeerConnection = window.RTCPeerConnection;
                }
                if (!window.RTCSessionDescription) {
                    window.RTCSessionDescription = function(init) { Object.assign(this, init || {}); };
                }
                if (!window.RTCIceCandidate) {
                    window.RTCIceCandidate = function(init) { Object.assign(this, init || {}); };
                }
                if (!navigator.mediaDevices) {
                    navigator.mediaDevices = {};
                }
                if (!navigator.mediaDevices.getUserMedia) {
                    navigator.mediaDevices.getUserMedia = function() {
                        return Promise.reject(new DOMException('Not supported', 'NotSupportedError'));
                    };
                }
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(webrtcPolyfill)

        // Inject console override script that runs at document start
        let consoleOverride = WKUserScript(source: """
            (function() {
                // Per-frame spatial-audio diagnostic spam from OpenAudioMC's worklet.
                // Each speaker emits one of these every few audio frames — they crowd
                // out the useful [DEBUG]/[MediaTrack]/[Playlist] lines that name actual
                // sounds. The init line ("Cardioid spatial processor initialized") is
                // kept since it's once-per-speaker.
                var WORKLET_SPATIAL_PREFIX = 'Worklet: Cardioid Spatial:';
                function forward(level, origFn) {
                    return function() {
                        var msg = Array.prototype.slice.call(arguments).map(function(a) {
                            try { return typeof a === 'object' ? JSON.stringify(a) : String(a); }
                            catch(e) { return String(a); }
                        }).join(' ');
                        if (msg.indexOf(WORKLET_SPATIAL_PREFIX) !== 0) {
                            try { window.webkit.messageHandlers.nativeLog.postMessage({level: level, message: msg}); }
                            catch(e) {}
                        }
                        origFn.apply(console, arguments);
                    };
                }
                console.log = forward('log', console.log);
                console.warn = forward('warn', console.warn);
                console.error = forward('error', console.error);
                console.info = forward('info', console.info);
                window.addEventListener('error', function(e) {
                    try { window.webkit.messageHandlers.nativeLog.postMessage({level: 'uncaught', message: e.message + ' at ' + e.filename + ':' + e.lineno}); }
                    catch(ex) {}
                });
                window.addEventListener('unhandledrejection', function(e) {
                    try { window.webkit.messageHandlers.nativeLog.postMessage({level: 'rejection', message: e.reason ? String(e.reason) : 'unknown'}); }
                    catch(ex) {}
                });
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(consoleOverride)

        // Inject AudioContext polyfill to auto-resume (bypass autoplay policy)
        let audioPolyfill = WKUserScript(source: """
            (function() {
                var _OrigAC = window.AudioContext || window.webkitAudioContext;
                if (!_OrigAC) return;

                // Keep only weak references. A strong context array retains detached media
                // graphs (and their WebKit GPU resources) for the lifetime of the page.
                var _contextRefs = typeof WeakRef === 'function' ? [] : null;
                var _origResume = _OrigAC.prototype.resume;

                function liveContexts() {
                    if (!_contextRefs) return [];
                    var live = [];
                    var retained = [];
                    _contextRefs.forEach(function(ref) {
                        var ctx = ref.deref();
                        if (ctx) {
                            live.push(ctx);
                            retained.push(ref);
                        }
                    });
                    _contextRefs = retained;
                    return live;
                }

                _OrigAC.prototype.resume = function() {
                    return _origResume.call(this).catch(function() {});
                };

                try {
                    var _PatchedAC = new Proxy(_OrigAC, {
                        construct: function(target, args) {
                            var ctx = Reflect.construct(target, args);
                            if (_contextRefs) _contextRefs.push(new WeakRef(ctx));
                            setTimeout(function() {
                                if (ctx.state !== 'running') ctx.resume();
                            }, 50);
                            return ctx;
                        }
                    });
                    _PatchedAC.prototype = _OrigAC.prototype;
                    window.AudioContext = _PatchedAC;
                    if (window.webkitAudioContext) window.webkitAudioContext = _PatchedAC;
                } catch(e) {}

                window.__nra_resumeAllAudio = function() {
                    liveContexts().forEach(function(ctx) {
                        if (ctx.state !== 'running') ctx.resume();
                    });
                };

                window.__nra_shutdownAudio = function() {
                    document.querySelectorAll('audio,video').forEach(function(media) {
                        try { media.pause(); } catch(e) {}
                        try { media.removeAttribute('src'); media.load(); } catch(e) {}
                    });
                    var contexts = liveContexts();
                    if (_contextRefs) _contextRefs.length = 0;
                    return Promise.allSettled(contexts.map(function(ctx) {
                        try { return ctx.close(); } catch(e) { return Promise.resolve(); }
                    }));
                };
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(audioPolyfill)

        // WebKit audio-volume fix. Once an <audio> is routed through
        // createMediaElementSource() WebKit ignores element.volume, so two pieces work
        // together: (1) intercept createMediaElementSource to splice in a volGain GainNode,
        // and (2) override the HTMLMediaElement.prototype volume setter to mirror the value
        // into that gain and to force .muted when v<=0 (so volume 0 actually silences,
        // including OAM's detached-`new Audio()` music elements that never reach a
        // MediaElementSource). This deliberately does NOT touch window.Audio,
        // document.createElement, HTMLMediaElement.prototype.play, or window.WebSocket — a
        // per-element registry that hooked those to back /oa list|stop|vol was removed
        // because it retained every Audio element forever and leaked GPU/media memory.
        let audioVolumeFix = WKUserScript(source: """
            (function () {
              if (window.__nra_volume_fix) return;
              window.__nra_volume_fix = true;

              // Gains spliced in by the createMediaElementSource hook below, keyed by media
              // element in a WeakMap so finished elements are GC'd normally (no retention).
              var elementGains = new WeakMap();

              // volume setter: (a) write through to the native setter; (b) mirror into the
              // volGain for createMediaElementSource-routed spatial speakers; (c) force
              // .muted when v<=0 so volume 0 actually silences despite WebKit's quirk.
              var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
              if (desc && desc.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                  get: desc.get,
                  set: function (v) {
                    desc.set.call(this, v);
                    var g = elementGains.get(this);
                    if (g) g.gain.value = v;
                    var shouldMute = !(v > 0);
                    if (this.muted !== shouldMute) this.muted = shouldMute;
                  },
                  configurable: true,
                  enumerable: true
                });
              }

              // createMediaElementSource interception: OAM's CustomSpatialRenderer wires
              // <audio> -> MediaElementSource -> worklet -> gain -> dest, and the worklet runs
              // at unity regardless of element.volume. Splice a volGain between the source and
              // whatever it connects to, mirroring element.volume into it so master-volume 0
              // actually mutes spatial speakers.
              if (typeof AudioContext !== 'undefined' || typeof webkitAudioContext !== 'undefined') {
                var AC = window.AudioContext || window.webkitAudioContext;
                var _createMES = AC.prototype.createMediaElementSource;
                AC.prototype.createMediaElementSource = function (mediaEl) {
                  var sourceNode = _createMES.call(this, mediaEl);
                  var volGain = this.createGain();
                  volGain.gain.value = mediaEl.volume;
                  elementGains.set(mediaEl, volGain);
                  sourceNode.connect(volGain);
                  sourceNode.connect = function () {
                    return volGain.connect.apply(volGain, arguments);
                  };
                  sourceNode.disconnect = function () {
                    return volGain.disconnect.apply(volGain, arguments);
                  };
                  return sourceNode;
                };
              }
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(audioVolumeFix)

        // OpenAudioMC can lose ownership of a MediaTrack while its async play path is pending.
        // This happens both when destroy() removes the track's `ended` listener during load(), and
        // when applyStartDateIfAny() stops an expired pickup before its first play but play() keeps
        // going anyway. The latter removes the track from its channel while leaving its listener
        // attached, so the resulting audio is no longer reachable by /volume. Track only weak
        // element/listener state and reject either stale late play. WebRTC/voice elements use
        // srcObject and are intentionally excluded.
        let staleMediaPlayGuard = WKUserScript(source: """
            (function () {
              if (window.__nra_stale_media_play_guard) return;
              window.__nra_stale_media_play_guard = true;

              var endedHandlers = new WeakMap();
              var allowedPlayAttempted = new WeakSet();
              var stoppedBeforeFirstPlay = new WeakSet();
              var nativeAddEventListener = HTMLMediaElement.prototype.addEventListener;
              var nativeRemoveEventListener = HTMLMediaElement.prototype.removeEventListener;

              // Diagnostics must be able to observe native media events without becoming part
              // of the page's MediaTrack ownership signal below. Keep the bypass non-enumerable
              // and narrowly scoped to adding an event listener on a media element.
              Object.defineProperty(window, '__nra_add_native_media_listener', {
                value: function (media, type, listener, options) {
                  return nativeAddEventListener.call(media, type, listener, options);
                },
                configurable: false,
                enumerable: false,
                writable: false
              });

              HTMLMediaElement.prototype.addEventListener = function (type, listener, options) {
                if (type === 'ended' && listener) {
                  var handlers = endedHandlers.get(this);
                  if (!handlers) {
                    handlers = new Set();
                    endedHandlers.set(this, handlers);
                  }
                  handlers.add(listener);
                }
                return nativeAddEventListener.call(this, type, listener, options);
              };

              HTMLMediaElement.prototype.removeEventListener = function (type, listener, options) {
                if (type === 'ended' && listener) {
                  var handlers = endedHandlers.get(this);
                  if (handlers) {
                    handlers.delete(listener);
                    if (handlers.size === 0) endedHandlers.delete(this);
                  }
                }
                return nativeRemoveEventListener.call(this, type, listener, options);
              };

              var nativePause = HTMLMediaElement.prototype.pause;
              HTMLMediaElement.prototype.pause = function () {
                var handlers = endedHandlers.get(this);
                var hasEndedHandler = !!(handlers && handlers.size);
                var hasUrlSource = !!(this.currentSrc || this.src);
                if (this.tagName === 'AUDIO' && !allowedPlayAttempted.has(this)
                    && this.paused && this.currentTime <= 0.001
                    && !document.contains(this) && hasUrlSource
                    && !this.srcObject && hasEndedHandler) {
                  stoppedBeforeFirstPlay.add(this);
                }
                return nativePause.apply(this, arguments);
              };

              var nativePlay = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                var handlers = endedHandlers.get(this);
                var hasEndedHandler = !!(handlers && handlers.size);
                var hasUrlSource = !!(this.currentSrc || this.src);
                var isDetached = !document.contains(this);
                if (this.tagName === 'AUDIO' && isDetached && hasUrlSource
                    && !this.srcObject
                    && (!hasEndedHandler || stoppedBeforeFirstPlay.has(this))) {
                  try { this.pause(); } catch (e) {}
                  return Promise.reject(new DOMException(
                    'Detached OpenAudioMC media lost ownership before playback started',
                    'AbortError'));
                }
                allowedPlayAttempted.add(this);
                return nativePlay.apply(this, arguments);
              };
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(staleMediaPlayGuard)

        // Temporary diagnostics for duplicate media events and late playback. All state is weak.
        let audioDiagnostics = WKUserScript(source: """
            (function () {
              if (window.__nra_audio_trace) return;
              window.__nra_audio_trace = true;

              var traceStartedAt = performance.now();
              function trace(message) {
                var full = '[IMF-AUDIO-TRACE +' + Math.round(performance.now() - traceStartedAt)
                  + 'ms] ' + message;
                try {
                  window.webkit.messageHandlers.nativeLog.postMessage({level: 'trace', message: full});
                } catch (e) {}
              }
              function hashText(value) {
                var text = String(value || '');
                var hash = 2166136261;
                for (var i = 0; i < text.length; i++) {
                  hash ^= text.charCodeAt(i);
                  hash = Math.imul(hash, 16777619);
                }
                return (hash >>> 0).toString(16).padStart(8, '0');
              }
              function numberOrDash(value) {
                return typeof value === 'number' && isFinite(value)
                  ? Math.round(value * 1000) / 1000 : '-';
              }

              // Summarize Socket.IO media packets without logging signed URLs or credentials.
              function inspectRelay(raw, transport) {
                if (typeof raw !== 'string') return;
                raw.split('\\u001e').forEach(function (frame) {
                  var arrayStart = frame.indexOf('[');
                  if (arrayStart < 0) return;
                  var packet;
                  try { packet = JSON.parse(frame.slice(arrayStart)); } catch (e) { return; }
                  if (!Array.isArray(packet) || packet[0] !== 'data' || !packet[1]) return;
                  var envelope = packet[1];
                  var type = String(envelope.type || '').split('.').pop();
                  var payload = envelope.payload || {};
                  if (type === 'ClientCreateMediaPayload') {
                    var media = payload.media || {};
                    var startTime = Date.parse(media.startInstant || '');
                    var startAgeMs = Number.isFinite(startTime) ? Date.now() - startTime : NaN;
                    trace('RELAY ' + transport + ' CREATE id=' + String(media.mediaId)
                      + ' src=' + hashText(media.source) + ' loop=' + !!media.loop
                      + ' pickup=' + !!media.doPickup
                      + ' volume=' + numberOrDash(media.volume)
                      + ' offsetMs=' + numberOrDash(media.startAtMillis)
                      + ' startAgeMs=' + numberOrDash(startAgeMs)
                      + ' fadeMs=' + numberOrDash(media.fadeTime));
                  } else if (type === 'ClientDestroyMediaPayload') {
                    trace('RELAY ' + transport + ' DESTROY id=' + String(payload.soundId || '-')
                      + ' all=' + !!payload.all + ' fadeMs=' + numberOrDash(payload.fadeTime));
                  } else if (type === 'ClientUpdateMediaPayload') {
                    var options = payload.mediaOptions || {};
                    trace('RELAY ' + transport + ' UPDATE id=' + String(options.target || '-')
                      + ' volume=' + numberOrDash(options.volume)
                      + ' speed=' + numberOrDash(options.speed));
                  } else if (type === 'ClientVolumePayload') {
                    trace('RELAY ' + transport + ' MASTER_VOLUME value=' + numberOrDash(payload.volume));
                  }
                });
              }

              var NativeWebSocket = window.WebSocket;
              if (NativeWebSocket) {
                try {
                  window.WebSocket = new Proxy(NativeWebSocket, {
                    construct: function (target, args, newTarget) {
                      var socket = Reflect.construct(target, args, newTarget);
                      socket.addEventListener('message', function (event) {
                        inspectRelay(event.data, 'websocket');
                      });
                      return socket;
                    }
                  });
                } catch (e) {
                  trace('WebSocket observer unavailable');
                }
              }

              var nativeXhrSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function () {
                this.addEventListener('load', function () {
                  try {
                    if (String(this.responseURL || '').indexOf('socket.io') >= 0) {
                      inspectRelay(this.responseText, 'polling');
                    }
                  } catch (e) {}
                });
                return nativeXhrSend.apply(this, arguments);
              };

              var mediaIds = new WeakMap();
              var mediaStates = new WeakMap();
              var pageEndedHandlers = new WeakMap();
              var diagnosticEndedHandlers = new WeakSet();
              var diagnosticCallbacks = new WeakMap();
              var nextMediaId = 1;
              function mediaId(media) {
                var id = mediaIds.get(media);
                if (!id) {
                  id = nextMediaId++;
                  mediaIds.set(media, id);
                  trace('MEDIA_OBSERVED media#' + id + ' tag=' + String(media.tagName || 'AUDIO'));
                }
                return id;
              }
              function sourceHash(media) {
                return hashText(media.currentSrc || media.src || media.getAttribute('src') || '');
              }
              function summary(media) {
                var handlers = pageEndedHandlers.get(media);
                return 'media#' + mediaId(media) + ' src=' + sourceHash(media)
                  + ' time=' + numberOrDash(media.currentTime)
                  + ' volume=' + numberOrDash(media.volume)
                  + ' muted=' + !!media.muted + ' paused=' + !!media.paused
                  + ' detached=' + !document.contains(media)
                  + ' endedHandlers=' + (handlers ? handlers.size : 0);
              }
              function stateFor(media) {
                var state = mediaStates.get(media);
                if (!state) {
                  state = {};
                  mediaStates.set(media, state);
                }
                return state;
              }

              var nativeAddEventListener = HTMLMediaElement.prototype.addEventListener;
              var nativeRemoveEventListener = HTMLMediaElement.prototype.removeEventListener;
              HTMLMediaElement.prototype.addEventListener = function (type, listener, options) {
                if (this.tagName === 'AUDIO' && type === 'ended' && listener
                    && !diagnosticEndedHandlers.has(listener)) {
                  var handlers = pageEndedHandlers.get(this);
                  if (!handlers) {
                    handlers = new Set();
                    pageEndedHandlers.set(this, handlers);
                  }
                  handlers.add(listener);
                  trace('ENDED_HANDLER_ADD ' + summary(this));
                }
                return nativeAddEventListener.call(this, type, listener, options);
              };
              HTMLMediaElement.prototype.removeEventListener = function (type, listener, options) {
                if (this.tagName === 'AUDIO' && type === 'ended' && listener
                    && !diagnosticEndedHandlers.has(listener)) {
                  var handlers = pageEndedHandlers.get(this);
                  if (handlers) {
                    handlers.delete(listener);
                    trace('ENDED_HANDLER_REMOVE ' + summary(this));
                    if (!handlers.size) pageEndedHandlers.delete(this);
                  }
                }
                return nativeRemoveEventListener.call(this, type, listener, options);
              };

              function attachEvents(media) {
                if (diagnosticCallbacks.has(media)) return;
                var callbacks = {};
                ['ended', 'emptied', 'error', 'abort'].forEach(function (type) {
                  callbacks[type] = function () { trace(type.toUpperCase() + ' ' + summary(media)); };
                  diagnosticEndedHandlers.add(callbacks[type]);
                  window.__nra_add_native_media_listener(media, type, callbacks[type]);
                });
                diagnosticCallbacks.set(media, callbacks);
              }

              var nativePlay = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                if (this.tagName === 'AUDIO') {
                  attachEvents(this);
                  trace('PLAY ' + summary(this));
                }
                var result = nativePlay.apply(this, arguments);
                if (this.tagName === 'AUDIO' && result && typeof result.catch === 'function') {
                  var media = this;
                  result.catch(function (error) {
                    trace('PLAY_REJECTED ' + summary(media) + ' error=' + String(error));
                  });
                }
                return result;
              };
              var nativePause = HTMLMediaElement.prototype.pause;
              HTMLMediaElement.prototype.pause = function () {
                if (this.tagName === 'AUDIO') trace('PAUSE ' + summary(this));
                return nativePause.apply(this, arguments);
              };

              function observeProperty(name) {
                var descriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, name);
                if (!descriptor || !descriptor.set) return;
                Object.defineProperty(HTMLMediaElement.prototype, name, {
                  get: descriptor.get,
                  set: function (value) {
                    var state = stateFor(this);
                    descriptor.set.call(this, value);
                    if (this.tagName !== 'AUDIO') return;
                    var current = this[name];
                    var shouldLog = name !== 'volume'
                      || state.volume === undefined
                      || (state.volume <= 0) !== (current <= 0)
                      || Math.abs(state.volume - current) >= 0.1;
                    state[name] = current;
                    var rendered = name === 'src' ? hashText(current) : String(current);
                    if (shouldLog) trace('SET_' + name.toUpperCase() + '=' + rendered
                      + ' ' + summary(this));
                  },
                  configurable: descriptor.configurable,
                  enumerable: descriptor.enumerable
                });
              }
              observeProperty('src');
              observeProperty('volume');
              observeProperty('muted');
              trace('diagnostics installed');
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(audioDiagnostics)

        // Create an offscreen window (1x1 pixel, hidden)
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1, height: 1),
            styleMask: [],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.orderOut(nil)

        webView = WKWebView(frame: window.contentView!.bounds, configuration: config)
        webView.autoresizingMask = [.width, .height]
        window.contentView?.addSubview(webView)

        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) NotRidingAlert/1.0 WKWebView"
    }

    func loadURL(_ urlString: String) {
        guard let url = URL(string: urlString) else {
            writeLine(jsonLine(["type": "error", "message": "Invalid URL: \(urlString)"]))
            return
        }
        webView.load(URLRequest(url: url))
    }

    func evaluateJS(_ js: String, id: String) {
        guard !destroyed else {
            writeLine(jsonLine(["type": "eval_result", "id": id,
                                "result": ["error": "WebView is shutting down"]]))
            return
        }
        webView.evaluateJavaScript(js) { result, error in
            if let error = error {
                writeLine(jsonLine([
                    "type": "eval_result",
                    "id": id,
                    "result": ["error": error.localizedDescription]
                ]))
                return
            }

            let resultDict: [String: Any]
            if let dict = result as? [String: Any] {
                resultDict = dict
            } else if let array = result as? [Any] {
                resultDict = ["value": array]
            } else if let boolVal = result as? Bool {
                resultDict = ["value": boolVal]
            } else if let numVal = result as? NSNumber {
                resultDict = ["value": numVal]
            } else if let strVal = result as? String {
                resultDict = ["value": strVal]
            } else {
                resultDict = [:]
            }

            writeLine(jsonLine([
                "type": "eval_result",
                "id": id,
                "result": resultDict
            ]))
        }
    }

    /// Stops media, detaches WebKit callbacks and closes the hidden window. The deadline makes
    /// teardown independent of a responsive JavaScript channel.
    func shutdown(completion: @escaping () -> Void) {
        guard !destroyed else {
            completion()
            return
        }
        shutdownCompletion = completion
        webView.evaluateJavaScript("""
            (async function() {
              if (window.__nra_shutdownAudio) await window.__nra_shutdownAudio();
              return true;
            })();
            """) { [weak self] _, _ in
                self?.finishShutdown()
            }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { [weak self] in
            self?.finishShutdown()
        }
    }

    func finishShutdown() {
        guard !destroyed else { return }
        destroyed = true
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "nativeLog")
        webView.configuration.userContentController.removeAllUserScripts()
        webView.removeFromSuperview()
        window.contentView = nil
        window.close()
        writeLine(jsonLine(["type": "webview_destroyed"]))
        let completion = shutdownCompletion
        shutdownCompletion = nil
        completion?()
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let url = webView.url?.absoluteString ?? ""
        writeLine(jsonLine(["type": "loaded", "url": url, "success": true]))
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        writeLine(jsonLine(["type": "error", "message": "Navigation failed: \(error.localizedDescription)"]))
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        writeLine(jsonLine(["type": "error", "message": "Load failed: \(error.localizedDescription)"]))
    }

    // WebKit's content-process (com.apple.WebKit.WebContent) crashed. Audio stops,
    // the WKWebView remains alive but blank, and the only way to recover is to
    // reload the URL. Emit a dedicated signal so the Java side can distinguish
    // this from a server-initiated session end (which has identical user-visible
    // symptoms: audio stops, slider disappears) and record it for /oa disconnects.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        writeLine(jsonLine(["type": "web_content_terminated"]))
    }

    // MARK: - WKUIDelegate (handle JS alerts, confirm, etc.)

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        completionHandler(true)
    }
}

// MARK: - Stdin command reader

class StdinReader {
    let manager: WebViewManager

    init(manager: WebViewManager) {
        self.manager = manager
    }

    func startReading() {
        let thread = Thread {
            self.readLoop()
        }
        thread.name = "StdinReader"
        thread.start()
    }

    private func readLoop() {
        while let line = readLine(strippingNewline: true) {
            guard !line.isEmpty else { continue }

            guard let data = line.data(using: .utf8),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cmd = json["cmd"] as? String else {
                continue
            }

            switch cmd {
            case "load":
                if let url = json["url"] as? String {
                    DispatchQueue.main.async {
                        self.manager.loadURL(url)
                    }
                }
            case "eval":
                if let js = json["js"] as? String, let id = json["id"] as? String {
                    DispatchQueue.main.async {
                        self.manager.evaluateJS(js, id: id)
                    }
                }
            case "quit":
                DispatchQueue.main.async {
                    self.manager.shutdown {
                        NSApplication.shared.terminate(nil)
                    }
                }
                return
            default:
                break
            }
        }

        DispatchQueue.main.async {
            self.manager.shutdown {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}

// MARK: - App delegate

class AppDelegate: NSObject, NSApplicationDelegate {
    var manager: WebViewManager!
    var reader: StdinReader!
    // Held for the process lifetime to keep App Nap from throttling this helper.
    var activityToken: NSObjectProtocol?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Disable App Nap. This helper runs offscreen (1x1 hidden window), so macOS
        // would otherwise classify it as a background app and coalesce/throttle its
        // timers during silent stretches — delaying OpenAudioMC's socket.io keepalive
        // pings until the relay declares the connection dead (a 1006 abnormal close).
        // .userInitiatedAllowingIdleSystemSleep prevents the throttling but still lets
        // the whole Mac sleep normally; we are not pinning the machine awake.
        activityToken = ProcessInfo.processInfo.beginActivity(
            options: .userInitiatedAllowingIdleSystemSleep,
            reason: "OpenAudioMC headless audio session keepalive")

        manager = WebViewManager()
        reader = StdinReader(manager: manager)
        reader.startReading()

        writeLine(jsonLine(["type": "ready"]))
    }

    func applicationWillTerminate(_ notification: Notification) {
        manager?.finishShutdown()
        if let token = activityToken {
            ProcessInfo.processInfo.endActivity(token)
            activityToken = nil
        }
        writeLine(jsonLine(["type": "helper_exiting"]))
    }
}

// MARK: - Main

let app = NSApplication.shared
app.setActivationPolicy(.accessory)  // No dock icon, no menu bar
let delegate = AppDelegate()
app.delegate = delegate
app.run()
