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

    override init() {
        let config = WKWebViewConfiguration()
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

                var _allContexts = [];
                var _origResume = _OrigAC.prototype.resume;

                _OrigAC.prototype.resume = function() {
                    return _origResume.call(this).catch(function() {});
                };

                try {
                    var _PatchedAC = new Proxy(_OrigAC, {
                        construct: function(target, args) {
                            var ctx = Reflect.construct(target, args);
                            _allContexts.push(ctx);
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
                    _allContexts.forEach(function(ctx) {
                        if (ctx.state !== 'running') ctx.resume();
                    });
                };
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(audioPolyfill)

        // Per-element audio registry. OpenAudioMC v367's MediaTrack class wraps
        // `new Audio()` (detached HTMLAudioElement, never appended to the DOM)
        // and drives volume through `this.audio.volume` directly — no
        // createMediaElementSource and no AudioContext in the music path. This
        // registry intercepts element creation so the Java side can enumerate
        // and control individual tracks (list / stop / per-element volume) in
        // addition to the React master-volume slider.
        //
        // Surface: window.__nra_audio
        //   .list({ includeAll }) -> [{id, tag, src, volume, paused, ...}]
        //   .setVolume(id, 0..1)
        //   .stop(id) | .pause(id) | .resume(id) | .stopAll()
        //   .events (capped log of register / volume changes)
        let audioRegistry = WKUserScript(source: """
            (function () {
              if (window.__nra_audio) return;
              var registry = {
                byId: new Map(),
                nextId: 1,
                events: [],
                pushEvent: function (kind, info) {
                  this.events.push({ t: Date.now(), kind: kind, info: info });
                  if (this.events.length > 200) this.events.shift();
                },
                isData: function (el) {
                  var s = (el && (el.currentSrc || el.src)) || '';
                  return s.indexOf('data:') === 0;
                },
                register: function (el, source) {
                  if (!el || el.__nra_id != null) return el.__nra_id;
                  var id = this.nextId++;
                  el.__nra_id = id;
                  this.byId.set(id, { el: el, createdAt: Date.now(), source: source || 'unknown' });
                  this.pushEvent('register', {
                    id: id, source: source,
                    src: (el.src || el.currentSrc || '').slice(0, 200)
                  });
                  return id;
                },
                summarize: function (rec) {
                  var el = rec.el;
                  return {
                    id: el.__nra_id,
                    tag: el.tagName,
                    paused: el.paused,
                    volume: el.volume,
                    muted: el.muted,
                    currentTime: el.currentTime,
                    duration: isFinite(el.duration) ? el.duration : null,
                    loop: el.loop,
                    ended: el.ended,
                    readyState: el.readyState,
                    src: (el.currentSrc || el.src || '').slice(0, 240),
                    createdAt: rec.createdAt,
                    source: rec.source,
                    inDom: document.contains(el),
                    isData: registry.isData(el)
                  };
                },
                list: function (opts) {
                  opts = opts || {};
                  var out = [];
                  registry.byId.forEach(function (rec) {
                    var s = registry.summarize(rec);
                    if (!opts.includeAll && (s.isData || s.ended)) return;
                    out.push(s);
                  });
                  return out;
                },
                listAll: function () { return this.list({ includeAll: true }); },
                setVolume: function (id, v) {
                  var rec = this.byId.get(id); if (!rec) return false;
                  rec.el.volume = Math.max(0, Math.min(1, v));
                  return true;
                },
                setMuted: function (id, m) {
                  var rec = this.byId.get(id); if (!rec) return false;
                  rec.el.muted = !!m;
                  return true;
                },
                stop: function (id) {
                  var rec = this.byId.get(id); if (!rec) return false;
                  try { rec.el.pause(); } catch (e) {}
                  try { rec.el.currentTime = 0; } catch (e) {}
                  return true;
                },
                pause: function (id) {
                  var rec = this.byId.get(id); if (!rec) return false;
                  try { rec.el.pause(); } catch (e) {}
                  return true;
                },
                resume: function (id) {
                  var rec = this.byId.get(id); if (!rec) return false;
                  try { rec.el.play(); } catch (e) {}
                  return true;
                },
                stopAll: function () {
                  var n = 0;
                  registry.byId.forEach(function (rec) {
                    if (registry.isData(rec.el)) return;
                    try { rec.el.pause(); n++; } catch (e) {}
                  });
                  return n;
                }
              };
              window.__nra_audio = registry;

              // 1) Audio constructor — caught at construct time so we record the element
              //    even if OAM never inserts it into the DOM (which it doesn't).
              var OrigAudio = window.Audio;
              if (OrigAudio) {
                window.Audio = function (src) {
                  var el = new OrigAudio(src);
                  registry.register(el, 'Audio()');
                  return el;
                };
                window.Audio.prototype = OrigAudio.prototype;
              }

              // 2) document.createElement('audio'|'video') — catches the rare path that
              //    builds elements manually instead of via new Audio().
              var _ce = document.createElement.bind(document);
              document.createElement = function (name, opts) {
                var el = _ce(name, opts);
                if (name && (name.toLowerCase() === 'audio' || name.toLowerCase() === 'video')) {
                  registry.register(el, 'createElement(' + name + ')');
                }
                return el;
              };

              // 3) play() — late-register safety net for any element created before our
              //    constructor hook (e.g., if an extension or earlier inline script
              //    cached a reference to the unpatched constructor).
              var _origPlay = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                registry.register(this, 'play()-late');
                return _origPlay.apply(this, arguments);
              };

              // 4) volume setter — three jobs:
              //    a) write the value through to the native setter (observability for engine)
              //    b) mirror into the per-element volGain inserted by the createMediaElementSource
              //       hook below, so spatial-routed speakers honor the value
              //    c) drive .muted from the value (true iff v<=0). This is the belt-and-
              //       suspenders against WebKit's quirk: even if (b) doesn't catch an
              //       element (e.g., the element was created before our injection),
              //       .muted is honored by a different WebKit code path so v=0 still
              //       silences the track.
              var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
              if (desc && desc.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                  get: desc.get,
                  set: function (v) {
                    desc.set.call(this, v);
                    var g = registry.elementGains && registry.elementGains.get(this);
                    if (g) g.gain.value = v;
                    var shouldMute = !(v > 0);
                    if (this.muted !== shouldMute) this.muted = shouldMute;
                    if (this.__nra_id != null) {
                      registry.pushEvent('volume', { id: this.__nra_id, v: v });
                    }
                  },
                  configurable: true,
                  enumerable: true
                });
              }

              // 4b) Restore the createMediaElementSource interception specifically for
              //     WebKit's audio.volume quirk: once an HTMLMediaElement has been routed
              //     through createMediaElementSource(), subsequent writes to element.volume
              //     are silently ignored by the engine (the graph runs at unity regardless).
              //     This bites OpenAudioMC's *speaker* path — OAM's CustomSpatialRenderer
              //     wires `<audio>` → MediaElementSource → workletNode → gainNode → dest,
              //     and the spatial worklet applies its own gain independent of element.volume.
              //     We insert a `volGain` GainNode between MediaElementSource and the next
              //     node it gets connected to, mirroring element.volume into volGain.gain so
              //     master-volume = 0 actually mutes spatial speakers. Music elements that
              //     never reach createMediaElementSource are unaffected.
              registry.elementGains = new WeakMap();
              if (typeof AudioContext !== 'undefined' || typeof webkitAudioContext !== 'undefined') {
                var AC = window.AudioContext || window.webkitAudioContext;
                var _createMES = AC.prototype.createMediaElementSource;
                AC.prototype.createMediaElementSource = function (mediaEl) {
                  var sourceNode = _createMES.call(this, mediaEl);
                  var volGain = this.createGain();
                  volGain.gain.value = mediaEl.volume;
                  registry.elementGains.set(mediaEl, volGain);
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

              // NOTE: a window.WebSocket wrapper used to live here (engine.io frame parsing for
              // /oa names + close-code capture). It was removed — observational data showed
              // sessions staying up far longer with it gone, and the per-frame parse / socket
              // diagnostics weren't worth the apparent relay-stability cost. We deliberately do
              // NOT touch window.WebSocket anymore.
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        // A/B test mode: when NRA_OA_TEST_MODE=1, skip ALL of our page-level injections
        // (registry, WebSocket wrapper, volGain createMediaElementSource hook, muted-driving
        // volume setter) so the page runs vanilla. The webrtc/console/audio polyfills above
        // are kept since they predate this work and are needed for basic playback. Used to
        // measure whether our modifications affect the relay disconnect rate. Disconnect
        // recording is Java-side and works in both modes.
        let testMode = ProcessInfo.processInfo.environment["NRA_OA_TEST_MODE"] == "1"
        if testMode {
            writeLine(jsonLine(["type": "console", "level": "info",
                                "message": "[NRA] OA test mode: page injections DISABLED"]))
        } else {
            config.userContentController.addUserScript(audioRegistry)
        }

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
                    NSApplication.shared.terminate(nil)
                }
                return
            default:
                break
            }
        }

        DispatchQueue.main.async {
            NSApplication.shared.terminate(nil)
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
}

// MARK: - Main

let app = NSApplication.shared
app.setActivationPolicy(.accessory)  // No dock icon, no menu bar
let delegate = AppDelegate()
app.delegate = delegate
app.run()
