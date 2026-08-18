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
//     {"type":"loaded","success":true}
//     {"type":"eval_result","id":"uuid","result":{...}}
//     {"type":"console","level":"log|warn|error","message":"..."}
//     {"type":"gpu_memory_health","pid":123,"physicalFootprintBytes":123456}
//     {"type":"gpu_memory_pressure","reason":"rapid-growth","mediaSnapshot":[...]}
//     {"type":"gpu_memory_monitor_failed","pid":123,"unavailableDurationMs":300000}
//     {"type":"error","message":"..."}

import AppKit
import Darwin
import Foundation
import WebKit

enum AudioInjectionMode: String {
    case legacyMinimum = "legacy-minimum"
    case legacyObserve = "legacy-observe"
    case legacyGuarded = "legacy-guarded"
    case managedLifecycle = "managed-lifecycle"

    static func selected(environment: [String: String] = ProcessInfo.processInfo.environment)
        -> AudioInjectionMode
    {
        switch environment["IMF_AUDIO_INJECTION_MODE"]?.lowercased() {
        case "legacy", "legacy-minimum", "minimum":
            return .legacyMinimum
        case "observe", "legacy-observe":
            return .legacyObserve
        case "guarded", "legacy-guarded":
            return .legacyGuarded
        case "managed", "managed-lifecycle", "full":
            return .managedLifecycle
        default:
            // Keep the stable May 2026-style playback path and add only the element-scoped
            // pre-native stale-play guard proven necessary by the Ariel trace.
            return .legacyGuarded
        }
    }
}

private let webKitGPUProcessPattern =
    "/XPCServices/com.apple.WebKit.GPU.xpc/Contents/MacOS/com.apple.WebKit.GPU"

/// Returns the current WebKit GPU service PIDs. Each standalone helper process gets its own
/// WebKit GPU service on macOS; taking the set before WKWebView creation lets us identify ours
/// without ever matching or terminating another application's WebKit process.
func webKitGPUProcessIDs() -> Set<Int32> {
    let process = Process()
    let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/pgrep")
    process.arguments = ["-f", webKitGPUProcessPattern]
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    do {
        try process.run()
        process.waitUntilExit()
    } catch {
        return []
    }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    guard let output = String(data: data, encoding: .utf8) else { return [] }
    return Set(output.split(whereSeparator: \.isWhitespace).compactMap { Int32($0) })
}

private let bytesPerMiB: UInt64 = 1024 * 1024

struct GPUProcessMemory {
    let physicalFootprintBytes: UInt64
    let residentBytes: UInt64
    let lifetimeMaxFootprintBytes: UInt64
}

/// Reads the same physical-footprint counter reported by vmmap and Activity Monitor. RSS is not
/// sufficient for WebKit/CoreMedia because compressed and reclaimable allocations can otherwise be
/// attributed to the parent application only after the incident has already ended.
func GPUProcessMemoryForPID(_ pid: Int32) -> GPUProcessMemory? {
    var usage = rusage_info_v4()
    let result = withUnsafeMutablePointer(to: &usage) { pointer in
        pointer.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) {
            proc_pid_rusage(pid, RUSAGE_INFO_V4, $0)
        }
    }
    guard result == 0, usage.ri_phys_footprint > 0 else { return nil }
    return GPUProcessMemory(
        physicalFootprintBytes: usage.ri_phys_footprint,
        residentBytes: usage.ri_resident_size,
        lifetimeMaxFootprintBytes: usage.ri_lifetime_max_phys_footprint
    )
}

struct GPUFootprintThresholds {
    static let productionAbsoluteBytes: UInt64 = 1536 * bytesPerMiB
    static let productionRapidGrowthBytes: UInt64 = 512 * bytesPerMiB
    static let productionRapidGrowthFloorBytes: UInt64 = 768 * bytesPerMiB
    static let productionRapidGrowthWindowMs: Int64 = 12_000
    static let productionAbsoluteConsecutiveSamples = 2

    let absoluteBytes: UInt64
    let rapidGrowthBytes: UInt64
    let rapidGrowthFloorBytes: UInt64
    let rapidGrowthWindowMs: Int64
    let absoluteConsecutiveSamples: Int

    init(environment: [String: String] = ProcessInfo.processInfo.environment) {
        func positiveUInt64(_ key: String, fallback: UInt64) -> UInt64 {
            guard let text = environment[key], let value = UInt64(text), value > 0 else {
                return fallback
            }
            return value
        }
        func positiveInt64(_ key: String, fallback: Int64) -> Int64 {
            guard let text = environment[key], let value = Int64(text), value > 0 else {
                return fallback
            }
            return value
        }
        func positiveInt(_ key: String, fallback: Int) -> Int {
            guard let text = environment[key], let value = Int(text), value > 0 else {
                return fallback
            }
            return value
        }

        absoluteBytes = positiveUInt64(
            "IMF_GPU_ABSOLUTE_LIMIT_BYTES",
            fallback: Self.productionAbsoluteBytes)
        rapidGrowthBytes = positiveUInt64(
            "IMF_GPU_RAPID_GROWTH_LIMIT_BYTES",
            fallback: Self.productionRapidGrowthBytes)
        rapidGrowthFloorBytes = positiveUInt64(
            "IMF_GPU_RAPID_GROWTH_FLOOR_BYTES",
            fallback: Self.productionRapidGrowthFloorBytes)
        rapidGrowthWindowMs = positiveInt64(
            "IMF_GPU_RAPID_GROWTH_WINDOW_MS",
            fallback: Self.productionRapidGrowthWindowMs)
        absoluteConsecutiveSamples = positiveInt(
            "IMF_GPU_ABSOLUTE_CONSECUTIVE_SAMPLES",
            fallback: Self.productionAbsoluteConsecutiveSamples)
    }

    init(
        absoluteBytes: UInt64,
        rapidGrowthBytes: UInt64,
        rapidGrowthFloorBytes: UInt64,
        rapidGrowthWindowMs: Int64,
        absoluteConsecutiveSamples: Int
    ) {
        self.absoluteBytes = absoluteBytes
        self.rapidGrowthBytes = rapidGrowthBytes
        self.rapidGrowthFloorBytes = rapidGrowthFloorBytes
        self.rapidGrowthWindowMs = rapidGrowthWindowMs
        self.absoluteConsecutiveSamples = absoluteConsecutiveSamples
    }
}

struct GPUFootprintEvaluation {
    let reason: String?
    let absoluteBreachSamples: Int
    let windowDeltaBytes: UInt64
    let windowDurationMs: Int64
}

struct GPUFootprintAvailabilityEvaluation {
    let hasSuccessfulSample: Bool
    let unavailableDurationMs: Int64
    let shouldRecycle: Bool
}

/// Recycles only when working footprint telemetry becomes continuously unavailable. A host where
/// proc_pid_rusage never worked must not enter a periodic helper restart loop.
struct GPUFootprintAvailabilityPolicy {
    static let productionUnavailableLimitMs: Int64 = 5 * 60 * 1_000

    let unavailableLimitMs: Int64
    private var lastSuccessfulSampleAtMs: Int64?

    init(environment: [String: String] = ProcessInfo.processInfo.environment) {
        if let text = environment["IMF_GPU_UNAVAILABLE_LIMIT_MS"],
           let value = Int64(text), value > 0 {
            unavailableLimitMs = value
        } else {
            unavailableLimitMs = Self.productionUnavailableLimitMs
        }
    }

    init(unavailableLimitMs: Int64) {
        self.unavailableLimitMs = unavailableLimitMs
    }

    mutating func reset() {
        lastSuccessfulSampleAtMs = nil
    }

    mutating func observeAvailable(timestampMs: Int64) {
        lastSuccessfulSampleAtMs = timestampMs
    }

    func observeUnavailable(timestampMs: Int64) -> GPUFootprintAvailabilityEvaluation {
        guard let lastSuccessfulSampleAtMs else {
            return GPUFootprintAvailabilityEvaluation(
                hasSuccessfulSample: false, unavailableDurationMs: 0, shouldRecycle: false)
        }
        let durationMs = max(0, timestampMs - lastSuccessfulSampleAtMs)
        return GPUFootprintAvailabilityEvaluation(
            hasSuccessfulSample: true,
            unavailableDurationMs: durationMs,
            shouldRecycle: durationMs >= unavailableLimitMs)
    }
}

struct GPUFootprintPolicy {
    private struct Sample {
        let timestampMs: Int64
        let footprintBytes: UInt64
    }

    let thresholds: GPUFootprintThresholds
    private var samples: [Sample] = []
    private var absoluteBreachSamples = 0

    init(thresholds: GPUFootprintThresholds) {
        self.thresholds = thresholds
    }

    mutating func reset() {
        samples.removeAll(keepingCapacity: true)
        absoluteBreachSamples = 0
    }

    mutating func observe(timestampMs: Int64, footprintBytes: UInt64) -> GPUFootprintEvaluation {
        let cutoff = timestampMs - thresholds.rapidGrowthWindowMs
        samples.removeAll { $0.timestampMs < cutoff }
        samples.append(Sample(timestampMs: timestampMs, footprintBytes: footprintBytes))

        if footprintBytes >= thresholds.absoluteBytes {
            absoluteBreachSamples += 1
        } else {
            absoluteBreachSamples = 0
        }

        let baseline = samples.first ?? Sample(
            timestampMs: timestampMs, footprintBytes: footprintBytes)
        let windowDelta = footprintBytes >= baseline.footprintBytes
            ? footprintBytes - baseline.footprintBytes : 0
        let windowDuration = max(0, timestampMs - baseline.timestampMs)

        let reason: String?
        if absoluteBreachSamples >= thresholds.absoluteConsecutiveSamples {
            reason = "absolute-footprint"
        } else if footprintBytes >= thresholds.rapidGrowthFloorBytes
            && windowDuration > 0 && windowDelta >= thresholds.rapidGrowthBytes {
            reason = "rapid-growth"
        } else {
            reason = nil
        }
        return GPUFootprintEvaluation(
            reason: reason,
            absoluteBreachSamples: absoluteBreachSamples,
            windowDeltaBytes: windowDelta,
            windowDurationMs: windowDuration)
    }
}

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
    let audioInjectionMode: AudioInjectionMode
    let audioDiagnosticsEnabled: Bool
    private var destroyed = false
    private var shutdownCompletion: (() -> Void)?
    private let GPUProcessIDsBeforeWebView: Set<Int32>
    private var observedGPUProcessID: Int32?
    private var GPUWatchdog: Timer?
    private var GPUFootprintPolicyState = GPUFootprintPolicy(thresholds: GPUFootprintThresholds())
    private var GPUFootprintAvailabilityState = GPUFootprintAvailabilityPolicy()
    private var lastGPUFootprintTelemetryAtMs: Int64 = 0
    private var GPUFootprintReadFailures = 0
    private var pendingGPUMemoryPressurePayload: [String: Any]?
    private let GPUFootprintTelemetryIntervalMs: Int64 = {
        let value = ProcessInfo.processInfo.environment["IMF_GPU_TELEMETRY_INTERVAL_MS"]
            .flatMap(Int64.init) ?? 15_000
        return max(1_000, value)
    }()
    private var preferredVolumeScriptInstalled = false

    override init() {
        GPUProcessIDsBeforeWebView = webKitGPUProcessIDs()
        audioInjectionMode = AudioInjectionMode.selected()
        audioDiagnosticsEnabled = ProcessInfo.processInfo.environment["IMF_AUDIO_DIAGNOSTICS"] == "1"
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
                var _contextsCreated = 0;
                var _resumeAttempts = 0;
                var _lastResumeAttemptAt = 0;
                var _lastResumeSuccessAt = 0;

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

                function resumeContext(ctx) {
                    if (!ctx || ctx.state === 'running' || ctx.state === 'closed') {
                        return Promise.resolve(ctx ? ctx.state : 'missing');
                    }
                    _resumeAttempts++;
                    _lastResumeAttemptAt = Date.now();
                    return _origResume.call(ctx).then(function() {
                        if (ctx.state === 'running') _lastResumeSuccessAt = Date.now();
                        return ctx.state;
                    }, function() {
                        return ctx.state;
                    });
                }

                _OrigAC.prototype.resume = function() {
                    return resumeContext(this);
                };

                try {
                    var _PatchedAC = new Proxy(_OrigAC, {
                        construct: function(target, args) {
                            var ctx = Reflect.construct(target, args);
                            _contextsCreated++;
                            if (_contextRefs) _contextRefs.push(new WeakRef(ctx));
                            setTimeout(function() {
                                resumeContext(ctx);
                            }, 50);
                            return ctx;
                        }
                    });
                    _PatchedAC.prototype = _OrigAC.prototype;
                    window.AudioContext = _PatchedAC;
                    if (window.webkitAudioContext) window.webkitAudioContext = _PatchedAC;
                } catch(e) {}

                window.__nra_resumeAllAudio = function() {
                    return Promise.allSettled(liveContexts().map(resumeContext));
                };

                window.__nra_audio_context_health = function() {
                    var counts = {running: 0, suspended: 0, interrupted: 0, closed: 0, other: 0};
                    var contexts = liveContexts();
                    contexts.forEach(function(ctx) {
                        var state = String(ctx.state || 'other');
                        if (Object.prototype.hasOwnProperty.call(counts, state)) counts[state]++;
                        else counts.other++;
                    });
                    return {
                        created: _contextsCreated,
                        live: contexts.length,
                        running: counts.running,
                        suspended: counts.suspended,
                        interrupted: counts.interrupted,
                        closed: counts.closed,
                        other: counts.other,
                        resumeAttempts: _resumeAttempts,
                        lastResumeAttemptAt: _lastResumeAttemptAt,
                        lastResumeSuccessAt: _lastResumeSuccessAt
                    };
                };

                window.__nra_shutdownAudio = function() {
                    if (window.__nra_dispose_all_media) {
                        window.__nra_dispose_all_media('session-shutdown');
                    } else {
                        document.querySelectorAll('audio,video').forEach(function(media) {
                            try { media.pause(); } catch(e) {}
                            try { media.removeAttribute('src'); media.load(); } catch(e) {}
                        });
                    }
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
              var volumePolicyForcedMute = new WeakSet();

              function volumePolicyForcesSilence(media) {
                var masterVolume = Number(window.__nra_master_volume_percent);
                return window.__nra_volume_gate_active === true
                  || (isFinite(masterVolume) && masterVolume <= 0)
                  || !(media.volume > 0);
              }

              function applyMediaVolume(media) {
                var forceSilence = volumePolicyForcesSilence(media);
                var gain = elementGains.get(media);
                if (gain) gain.gain.value = forceSilence ? 0 : media.volume;
                if (forceSilence) {
                  if (!media.muted) {
                    volumePolicyForcedMute.add(media);
                    media.muted = true;
                  }
                } else if (volumePolicyForcedMute.has(media)) {
                  volumePolicyForcedMute.delete(media);
                  media.muted = false;
                }
              }

              Object.defineProperty(window, '__nra_apply_media_volume', {
                value: applyMediaVolume, configurable: false, enumerable: false, writable: false
              });
              Object.defineProperty(window, '__nra_media_volume_snapshot', {
                value: function (media) {
                  var gain = elementGains.get(media);
                  return {
                    volume: Number(media.volume),
                    muted: media.muted === true,
                    forcedMute: volumePolicyForcedMute.has(media),
                    gain: gain ? Number(gain.gain.value) : null
                  };
                },
                configurable: false, enumerable: false, writable: false
              });

              // volume setter: (a) write through to the native setter; (b) mirror into the
              // volGain for createMediaElementSource-routed spatial speakers; (c) force
              // .muted when v<=0 so volume 0 actually silences despite WebKit's quirk.
              var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
              if (desc && desc.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                  get: desc.get,
                  set: function (v) {
                    desc.set.call(this, v);
                    applyMediaVolume(this);
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
                  elementGains.set(mediaEl, volGain);
                  applyMediaVolume(mediaEl);
                  var sourceConnect = sourceNode.connect.bind(sourceNode);
                  var sourceDisconnect = sourceNode.disconnect.bind(sourceNode);
                  var sourceConnectedToGain = false;
                  sourceNode.connect = function () {
                    if (!sourceConnectedToGain) {
                      sourceConnect(volGain);
                      sourceConnectedToGain = true;
                    }
                    return volGain.connect.apply(volGain, arguments);
                  };
                  sourceNode.disconnect = function () {
                    var result = volGain.disconnect.apply(volGain, arguments);
                    if (arguments.length === 0) {
                      try { sourceDisconnect(); } catch (e) {}
                      sourceConnectedToGain = false;
                    }
                    return result;
                  };
                  if (window.__nra_register_media_graph) {
                    window.__nra_register_media_graph(mediaEl, {
                      context: this,
                      source: sourceNode,
                      gain: volGain,
                      disconnect: function () {
                        try { sourceDisconnect(); } catch (e) {}
                        sourceConnectedToGain = false;
                        try { volGain.disconnect(); } catch (e) {}
                      }
                    });
                  }
                  return sourceNode;
                };
              }
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        // Unified per-media lifecycle. OpenAudioMC creates detached new Audio() elements, so DOM
        // queries cannot enumerate them. Keep a strong reference only while an element is live,
        // then release its listeners, URL/srcObject and Web Audio graph at every terminal edge.
        let staleMediaPlayGuard = WKUserScript(source: """
            (function () {
              if (window.__nra_stale_media_play_guard) return;
              window.__nra_stale_media_play_guard = true;

              var states = new WeakMap();
              var liveMedia = new Set();
              var createdCount = 0;
              var disposedCount = 0;
              var totalErrorAbortCount = 0;
              var recentErrorAbortTimes = [];
              var lastSuccessfulPlayAt = 0;
              var lastPlayingEventAt = 0;
              var lastEndedAt = 0;
              var playAttemptCount = 0;
              var playResolvedCount = 0;
              var playRejectedCount = 0;
              var playPendingCount = 0;
              var stalePlayRejectedCount = 0;
              var muteSpeakersSuppressedCount = 0;
              var lastMuteSpeakersSuppressedAt = 0;
              var volumeRestoreCount = 0;
              var lastVolumeRestoreAt = 0;
              var disposeReasons = Object.create(null);
              var pendingServerMediaBySource = new Map();
              var mediaByServerId = new Map();
              var serverMediaPolicies = new Map();
              var relayObservers = [];
              var nativeAddEventListener = HTMLMediaElement.prototype.addEventListener;
              var nativeRemoveEventListener = HTMLMediaElement.prototype.removeEventListener;
              var nativePause = HTMLMediaElement.prototype.pause;
              var nativePlay = HTMLMediaElement.prototype.play;
              var srcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              var nativeJSONParse = JSON.parse;

              // Ariel media packets can request a global SPEAKER inhibitor. In practice that
              // inhibitor has outlived the intended cue hand-off and silenced unrelated ride
              // audio. Disable only muteSpeakers before OpenAudioMC consumes the packet; keep
              // muteRegions and every other media field unchanged.
              function disableSpeakerMuteInPacket(packet, countSuppression) {
                if (!Array.isArray(packet) || packet[0] !== 'data' || !packet[1]) return packet;
                var envelope = packet[1];
                var type = String(envelope.type || '').split('.').pop();
                if (type !== 'ClientCreateMediaPayload') return packet;
                var payload = envelope.payload || {};
                var serverMedia = payload.media;
                if (!serverMedia || serverMedia.muteSpeakers !== true) return packet;
                serverMedia.muteSpeakers = false;
                if (countSuppression) {
                  muteSpeakersSuppressedCount++;
                  lastMuteSpeakersSuppressedAt = Date.now();
                }
                return packet;
              }

              // Socket.IO parses both WebSocket and polling packets through JSON.parse. This
              // single narrow hook therefore covers both transports without proxying native
              // WebSocket/MessageEvent objects (which can fail WebKit brand checks).
              JSON.parse = function () {
                return disableSpeakerMuteInPacket(nativeJSONParse.apply(this, arguments), true);
              };

              function captureOf(options) {
                return options === true || !!(options && options.capture);
              }

              function hasURLSource(media) {
                return !!(media.currentSrc || media.src || media.getAttribute('src'));
              }

              function sourceKey(value) {
                var text = String(value || '');
                var hash = 2166136261;
                for (var i = 0; i < text.length; i++) {
                  hash ^= text.charCodeAt(i);
                  hash = Math.imul(hash, 16777619);
                }
                return text.length + ':' + (hash >>> 0).toString(16);
              }

              function sourceAliases(value) {
                var text = String(value || '');
                var aliases = new Set([sourceKey(text)]);
                var addToken = function (token) {
                  token = String(token || '').trim();
                  if (token) aliases.add('token:' + token.toLowerCase());
                };
                try { addToken(decodeURIComponent(text.split('/').pop().split('?')[0])); } catch (e) {}
                if (text.indexOf('files:') === 0 || text.indexOf('local:') === 0) {
                  addToken(text.slice(text.indexOf(':') + 1).split('/').pop());
                }
                try {
                  var url = new URL(text, document.baseURI);
                  addToken(url.pathname.split('/').pop());
                  ['fileName', 'v', 'id'].forEach(function (name) {
                    addToken(url.searchParams.get(name));
                  });
                } catch (e) {}
                return Array.from(aliases);
              }

              function removePendingServerMedia(serverMediaId) {
                Array.from(pendingServerMediaBySource.entries()).forEach(function (entry) {
                  var key = entry[0];
                  var pending = entry[1].filter(function (id) { return id !== serverMediaId; });
                  if (pending.length) pendingServerMediaBySource.set(key, pending);
                  else pendingServerMediaBySource.delete(key);
                });
              }

              function associateServerMedia(media, source) {
                var state = stateFor(media);
                if (state.serverMediaId) return;
                var serverMediaId = null;
                sourceAliases(source).some(function (key) {
                  var pending = pendingServerMediaBySource.get(key);
                  if (!pending || !pending.length) return false;
                  serverMediaId = pending.find(function (id) {
                    var candidate = serverMediaPolicies.get(id);
                    return candidate && candidate.associated !== true;
                  }) || null;
                  return serverMediaId !== null;
                });
                if (!serverMediaId) return;
                removePendingServerMedia(serverMediaId);
                state.serverMediaId = serverMediaId;
                var policy = serverMediaPolicies.get(serverMediaId);
                if (policy) policy.associated = true;
                state.muteSpeakers = !!(policy && policy.muteSpeakers);
                state.muteRegions = !!(policy && policy.muteRegions);
                state.channelVolume = policy && isFinite(policy.channelVolume)
                  ? Number(policy.channelVolume) : null;
                mediaByServerId.set(serverMediaId, media);
              }

              function isOpenAudioTrack(media, state) {
                return media.tagName === 'AUDIO' && !document.contains(media)
                  && hasURLSource(media) && !media.srcObject && state.pageEndedHandlers.size > 0;
              }

              function addInternalListener(media, state, type, listener, options) {
                state.listeners.push({type: type, listener: listener, options: options, internal: true});
                nativeAddEventListener.call(media, type, listener, options);
              }

              function scheduleDispose(media, state, reason, requireUnowned) {
                var generation = ++state.disposeScheduleGeneration;
                queueMicrotask(function () {
                  if (state.disposed || generation !== state.disposeScheduleGeneration) return;
                  if (requireUnowned && state.pageEndedHandlers.size > 0) return;
                  disposeMedia(media, reason);
                });
              }

              function stateFor(media) {
                var state = states.get(media);
                if (state) return state;
                state = {
                  disposed: false,
                  listeners: [],
                  pageEndedHandlers: new Set(),
                  graphs: new Set(),
                  successfulPlay: false,
                  stoppedBeforeFirstPlay: false,
                  serverMediaId: null,
                  muteSpeakers: false,
                  muteRegions: false,
                  channelVolume: null,
                  createdAt: Date.now(),
                  disposeScheduleGeneration: 0
                };
                states.set(media, state);
                liveMedia.add(media);
                createdCount++;

                addInternalListener(media, state, 'ended', function () {
                  lastEndedAt = Date.now();
                  scheduleDispose(media, state, 'ended', false);
                });
                addInternalListener(media, state, 'playing', function () {
                  lastPlayingEventAt = Date.now();
                });
                addInternalListener(media, state, 'emptied', function () {
                  scheduleDispose(media, state, 'emptied', false);
                });
                ['error', 'abort'].forEach(function (type) {
                  addInternalListener(media, state, type, function () {
                    var now = Date.now();
                    totalErrorAbortCount++;
                    recentErrorAbortTimes.push(now);
                    scheduleDispose(media, state, type, false);
                  });
                });
                return state;
              }

              function disposeMedia(media, reason) {
                if (!media) return false;
                var state = states.get(media);
                if (!state) state = stateFor(media);
                if (state.disposed) return false;
                state.disposed = true;
                state.disposeScheduleGeneration++;
                liveMedia.delete(media);
                disposedCount++;
                reason = String(reason || 'unspecified');
                disposeReasons[reason] = (disposeReasons[reason] || 0) + 1;

                state.listeners.forEach(function (record) {
                  try {
                    nativeRemoveEventListener.call(
                      media, record.type, record.listener, record.capture);
                  } catch (e) {}
                });
                state.listeners.length = 0;
                state.pageEndedHandlers.clear();
                state.graphs.forEach(function (graph) {
                  try {
                    if (graph && typeof graph.disconnect === 'function') graph.disconnect();
                    else {
                      if (graph && graph.source) graph.source.disconnect();
                      if (graph && graph.gain) graph.gain.disconnect();
                    }
                  } catch (e) {}
                });
                state.graphs.clear();
                if (state.serverMediaId && mediaByServerId.get(state.serverMediaId) === media) {
                  mediaByServerId.delete(state.serverMediaId);
                }
                if (state.serverMediaId) serverMediaPolicies.delete(state.serverMediaId);
                try { nativePause.call(media); } catch (e) {}
                [
                  'onabort', 'oncanplay', 'oncanplaythrough', 'ondurationchange', 'onemptied',
                  'onended', 'onerror', 'onloadeddata', 'onloadedmetadata', 'onloadstart',
                  'onpause', 'onplay', 'onplaying', 'onprogress', 'onratechange', 'onseeked',
                  'onseeking', 'onstalled', 'onsuspend', 'ontimeupdate', 'onvolumechange',
                  'onwaiting'
                ].forEach(function (property) {
                  try { media[property] = null; } catch (e) {}
                });
                try {
                  if ('srcObject' in media) media.srcObject = null;
                } catch (e) {}
                try { media.removeAttribute('src'); } catch (e) {}
                try { media.load(); } catch (e) {}
                return true;
              }

              function disposeAllMedia(reason) {
                Array.from(liveMedia).forEach(function (media) { disposeMedia(media, reason); });
                // Defensive coverage for page-owned media that never crossed an intercepted API.
                document.querySelectorAll('audio,video').forEach(function (media) {
                  disposeMedia(media, reason);
                });
              }

              function disposeUnownedMedia(reason) {
                Array.from(liveMedia).forEach(function (media) {
                  var state = states.get(media);
                  if (state && state.pageEndedHandlers.size === 0 && !media.srcObject) {
                    disposeMedia(media, reason);
                  }
                });
              }

              function expose(name, value) {
                Object.defineProperty(window, name, {
                  value: value, configurable: false, enumerable: false, writable: false
                });
              }

              function clampPercent(value) {
                value = Number(value);
                if (!isFinite(value)) return null;
                return Math.max(0, Math.min(100, value));
              }

              function channelVolumeFromCreate(payload, serverMedia) {
                var channelVolume = serverMedia.volume == null
                  ? null : clampPercent(serverMedia.volume);
                if (channelVolume === null) channelVolume = 100;
                var maxDistance = Number(payload.maxDistance);
                var distance = Number(payload.distance);
                if (isFinite(maxDistance) && maxDistance !== 0 && isFinite(distance)) {
                  channelVolume = clampPercent(Math.round(
                    (maxDistance - distance) / maxDistance * 100));
                }
                return channelVolume;
              }

              // OpenAudioMC multiplies the per-channel percentage by normalVolume before
              // writing HTMLMediaElement.volume. If the session started at master volume 0,
              // every element is left at volume 0. Restoring only the global value cannot
              // make those elements audible, so reconstruct that same calculation for known
              // server media at the two safe boundaries: a master-volume transition and play.
              function restoreMediaVolumeIfNeeded(media, state) {
                if (!media || !state || state.disposed) return false;
                var masterVolume = Number(window.__nra_master_volume_percent);
                var channelVolume = clampPercent(state.channelVolume);
                if (window.__nra_volume_gate_active === true || !isFinite(masterVolume)
                    || masterVolume <= 0 || channelVolume === null || channelVolume <= 0
                    || media.volume > 0) {
                  return false;
                }
                var expectedVolume = channelVolume / 100 * masterVolume / 100;
                if (!(expectedVolume > 0)) return false;
                try {
                  media.volume = Math.max(0, Math.min(1, expectedVolume));
                  // OAM server media is constructed unmuted. Clear a mute that survived the
                  // zero-volume gate even if another page write predated our forced-mute mark.
                  if (media.muted) media.muted = false;
                  if (window.__nra_apply_media_volume) window.__nra_apply_media_volume(media);
                  volumeRestoreCount++;
                  lastVolumeRestoreAt = Date.now();
                  return true;
                } catch (e) {
                  return false;
                }
              }

              function commitMasterVolume(volume) {
                volume = Number(volume);
                if (!isFinite(volume) || volume < 0 || volume > 100) return false;
                var previousVolume = Number(window.__nra_master_volume_percent);
                var gateWasActive = window.__nra_volume_gate_active === true;
                var changed = gateWasActive || !isFinite(previousVolume)
                  || previousVolume !== volume;
                window.__nra_master_volume_percent = volume;
                window.__nra_volume_gate_active = false;
                Array.from(liveMedia).forEach(function (media) {
                  var state = states.get(media);
                  if (changed && volume > 0) restoreMediaVolumeIfNeeded(media, state);
                  if (window.__nra_apply_media_volume) window.__nra_apply_media_volume(media);
                });
                return true;
              }

              expose('__nra_dispose_media', disposeMedia);
              expose('__nra_dispose_all_media', disposeAllMedia);
              expose('__nra_commit_master_volume', commitMasterVolume);
              // Fixed-size, privacy-safe evidence captured only when the native GPU watchdog
              // detects pressure. Never return the source URL or a signed server media ID.
              expose('__nra_media_snapshot', function (requestedLimit) {
                var limit = Number(requestedLimit);
                if (!isFinite(limit)) limit = 24;
                limit = Math.max(1, Math.min(32, Math.floor(limit)));
                var now = Date.now();
                function finiteNumber(value) {
                  value = Number(value);
                  return isFinite(value) ? value : null;
                }
                return Array.from(liveMedia).map(function (media) {
                  var state = states.get(media);
                  var playing = media.paused === false && media.ended !== true;
                  return {
                    sourceHash: sourceKey(media.currentSrc || media.src
                      || media.getAttribute('src') || ''),
                    serverMediaHash: state && state.serverMediaId
                      ? sourceKey(state.serverMediaId) : null,
                    ageMs: state ? Math.max(0, now - state.createdAt) : null,
                    detached: !document.contains(media),
                    playing: playing,
                    paused: media.paused === true,
                    ended: media.ended === true,
                    currentTime: finiteNumber(media.currentTime),
                    duration: finiteNumber(media.duration),
                    readyState: Number(media.readyState) || 0,
                    networkState: Number(media.networkState) || 0,
                    volume: finiteNumber(media.volume),
                    muted: media.muted === true,
                    hasSrcObject: !!media.srcObject,
                    successfulPlay: !!(state && state.successfulPlay),
                    endedOwners: state ? state.pageEndedHandlers.size : 0,
                    listeners: state ? state.listeners.length : 0,
                    graphs: state ? state.graphs.size : 0
                  };
                }).sort(function (left, right) {
                  if (left.playing !== right.playing) return left.playing ? -1 : 1;
                  return Number(right.ageMs || 0) - Number(left.ageMs || 0);
                }).slice(0, limit);
              });
              expose('__nra_media_health', function () {
                var cutoff = Date.now() - 15000;
                recentErrorAbortTimes = recentErrorAbortTimes.filter(function (time) {
                  return time >= cutoff;
                });
                var graphCount = 0;
                var muteSpeakersLive = 0;
                var muteRegionsLive = 0;
                var associatedLive = 0;
                var zeroVolumeLive = 0;
                var mutedLive = 0;
                var playingLive = 0;
                var playingSilentLive = 0;
                Array.from(liveMedia).forEach(function(media) {
                  var state = states.get(media);
                  if (!state) return;
                  graphCount += state.graphs.size;
                  if (state.muteSpeakers) muteSpeakersLive++;
                  if (state.muteRegions) muteRegionsLive++;
                  if (state.serverMediaId) associatedLive++;
                  var volumeState = window.__nra_media_volume_snapshot
                    ? window.__nra_media_volume_snapshot(media)
                    : {volume: Number(media.volume), muted: media.muted === true, gain: null};
                  var zeroVolume = !(volumeState.volume > 0);
                  var muted = volumeState.muted === true;
                  var zeroGain = volumeState.gain !== null && !(volumeState.gain > 0);
                  var playing = media.paused === false && media.ended !== true;
                  if (zeroVolume) zeroVolumeLive++;
                  if (muted) mutedLive++;
                  if (playing) playingLive++;
                  if (playing && (zeroVolume || muted || zeroGain)) playingSilentLive++;
                });
                return {
                  mode: 'managed-lifecycle',
                  created: createdCount,
                  disposed: disposedCount,
                  live: liveMedia.size,
                  lastSuccessfulPlayAt: lastSuccessfulPlayAt,
                  lastPlayingEventAt: lastPlayingEventAt,
                  lastEndedAt: lastEndedAt,
                  playAttempts: playAttemptCount,
                  playResolved: playResolvedCount,
                  playRejected: playRejectedCount,
                  playPending: playPendingCount,
                  stalePlayRejected: stalePlayRejectedCount,
                  graphsLive: graphCount,
                  muteSpeakersLive: muteSpeakersLive,
                  muteRegionsLive: muteRegionsLive,
                  muteSpeakersSuppressed: muteSpeakersSuppressedCount,
                  lastMuteSpeakersSuppressedAt: lastMuteSpeakersSuppressedAt,
                  associatedLive: associatedLive,
                  zeroVolumeLive: zeroVolumeLive,
                  mutedLive: mutedLive,
                  playingLive: playingLive,
                  playingSilentLive: playingSilentLive,
                  masterVolume: Number(window.__nra_master_volume_percent),
                  volumeGateActive: window.__nra_volume_gate_active === true,
                  volumeRestored: volumeRestoreCount,
                  lastVolumeRestoreAt: lastVolumeRestoreAt,
                  recentErrorAbort: recentErrorAbortTimes.length,
                  totalErrorAbort: totalErrorAbortCount,
                  disposeReasons: Object.assign({}, disposeReasons),
                  audioContexts: window.__nra_audio_context_health
                    ? window.__nra_audio_context_health() : null
                };
              });
              expose('__nra_register_media_graph', function (media, graph) {
                if (!media || !graph) return;
                var state = stateFor(media);
                if (state.disposed) {
                  try { if (typeof graph.disconnect === 'function') graph.disconnect(); } catch (e) {}
                  return;
                }
                state.graphs.add(graph);
              });
              expose('__nra_add_tracked_media_listener', function (media, type, listener, options) {
                if (!media || !listener) return;
                var state = stateFor(media);
                state.listeners.push({type: type, listener: listener, options: options, internal: true});
                return nativeAddEventListener.call(media, type, listener, options);
              });
              expose('__nra_add_relay_observer', function (observer) {
                if (typeof observer === 'function') relayObservers.push(observer);
              });
              HTMLMediaElement.prototype.addEventListener = function (type, listener, options) {
                if (!listener) return nativeAddEventListener.call(this, type, listener, options);
                var state = stateFor(this);
                var capture = captureOf(options);
                var exists = state.listeners.some(function (record) {
                  return !record.internal && record.type === type && record.listener === listener
                    && captureOf(record.options) === capture;
                });
                if (!exists) {
                  state.listeners.push({type: type, listener: listener, options: options, internal: false});
                  if (type === 'ended') state.pageEndedHandlers.add(listener);
                }
                return nativeAddEventListener.call(this, type, listener, options);
              };

              HTMLMediaElement.prototype.removeEventListener = function (type, listener, options) {
                var state = states.get(this);
                if (state && listener) {
                  var capture = captureOf(options);
                  state.listeners = state.listeners.filter(function (record) {
                    return record.internal || record.type !== type || record.listener !== listener
                      || captureOf(record.options) !== capture;
                  });
                  if (type === 'ended') {
                    state.pageEndedHandlers.delete(listener);
                    if (state.pageEndedHandlers.size === 0 && this.tagName === 'AUDIO'
                        && !document.contains(this) && hasURLSource(this) && !this.srcObject) {
                      scheduleDispose(this, state, 'ownership-removed', true);
                    }
                  }
                }
                return nativeRemoveEventListener.call(this, type, listener, options);
              };

              HTMLMediaElement.prototype.pause = function () {
                var state = stateFor(this);
                if (this.tagName === 'AUDIO' && !state.successfulPlay
                    && this.paused && this.currentTime <= 0.001
                    && isOpenAudioTrack(this, state)) {
                  state.stoppedBeforeFirstPlay = true;
                  scheduleDispose(this, state, 'stopped-before-first-play', false);
                }
                return nativePause.apply(this, arguments);
              };

              HTMLMediaElement.prototype.play = function () {
                var state = stateFor(this);
                var isDetached = !document.contains(this);
                playAttemptCount++;
                if (state.disposed || (this.tagName === 'AUDIO' && isDetached
                    && hasURLSource(this) && !this.srcObject
                    && (state.pageEndedHandlers.size === 0 || state.stoppedBeforeFirstPlay))) {
                  playRejectedCount++;
                  stalePlayRejectedCount++;
                  disposeMedia(this, state.stoppedBeforeFirstPlay
                    ? 'stale-play-stopped' : 'stale-play-unowned');
                  return Promise.reject(new DOMException(
                    'Detached OpenAudioMC media lost ownership before playback started',
                    'AbortError'));
                }
                restoreMediaVolumeIfNeeded(this, state);
                if (window.__nra_apply_media_volume) window.__nra_apply_media_volume(this);
                var result = nativePlay.apply(this, arguments);
                if (result && typeof result.then === 'function') {
                  playPendingCount++;
                  result.then(function () {
                    playPendingCount = Math.max(0, playPendingCount - 1);
                    playResolvedCount++;
                    if (!state.disposed) {
                      state.successfulPlay = true;
                      state.stoppedBeforeFirstPlay = false;
                      lastSuccessfulPlayAt = Date.now();
                    }
                  }, function () {
                    playPendingCount = Math.max(0, playPendingCount - 1);
                    playRejectedCount++;
                  });
                } else if (!state.disposed) {
                  playResolvedCount++;
                  state.successfulPlay = true;
                  lastSuccessfulPlayAt = Date.now();
                }
                return result;
              };

              if (srcDescriptor && srcDescriptor.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                  get: srcDescriptor.get,
                  set: function (value) {
                    stateFor(this);
                    var result = srcDescriptor.set.call(this, value);
                    associateServerMedia(this, value);
                    return result;
                  },
                  configurable: srcDescriptor.configurable,
                  enumerable: srcDescriptor.enumerable
                });
              }

              function inspectRelayLifecycle(raw) {
                if (typeof raw !== 'string') return;
                relayObservers.forEach(function (observer) {
                  try { observer(raw); } catch (e) {}
                });
                raw.split('\\u001e').forEach(function (frame) {
                  var arrayStart = frame.indexOf('[');
                  if (arrayStart < 0) return;
                  var packet;
                  try { packet = nativeJSONParse(frame.slice(arrayStart)); } catch (e) { return; }
                  disableSpeakerMuteInPacket(packet, false);
                  if (!Array.isArray(packet) || packet[0] !== 'data' || !packet[1]) return;
                  var envelope = packet[1];
                  var type = String(envelope.type || '').split('.').pop();
                  var payload = envelope.payload || {};
                  if (type === 'ClientCreateMediaPayload') {
                    var serverMedia = payload.media || {};
                    var serverMediaId = String(serverMedia.mediaId || '');
                    if (!serverMediaId || !serverMedia.source) return;
                    serverMediaPolicies.set(serverMediaId, {
                      muteSpeakers: !!serverMedia.muteSpeakers,
                      muteRegions: !!serverMedia.muteRegions,
                      channelVolume: channelVolumeFromCreate(payload, serverMedia),
                      associated: false
                    });
                    sourceAliases(serverMedia.source).forEach(function (key) {
                      var pending = pendingServerMediaBySource.get(key) || [];
                      pending.push(serverMediaId);
                      pendingServerMediaBySource.set(key, pending);
                    });
                    setTimeout(function () {
                      removePendingServerMedia(serverMediaId);
                    }, 30000);
                    return;
                  }
                  if (type === 'ClientUpdateMediaPayload') {
                    var mediaOptions = payload.mediaOptions || {};
                    var updatedId = String(mediaOptions.target || '');
                    var updatedVolume = clampPercent(mediaOptions.volume);
                    if (updatedId && updatedVolume !== null && updatedVolume > 0) {
                      var updatedPolicy = serverMediaPolicies.get(updatedId);
                      if (updatedPolicy) updatedPolicy.channelVolume = updatedVolume;
                      var updatedMedia = mediaByServerId.get(updatedId);
                      var updatedState = updatedMedia ? states.get(updatedMedia) : null;
                      if (updatedState) updatedState.channelVolume = updatedVolume;
                    }
                    return;
                  }
                  if (type !== 'ClientDestroyMediaPayload') return;
                  var fadeMs = Math.max(0, Math.min(Number(payload.fadeTime) || 0, 5000));
                  var runAfterFade = function (callback) {
                    if (fadeMs > 0) setTimeout(callback, fadeMs);
                    else queueMicrotask(callback);
                  };
                  if (payload.all) {
                    // Snapshot at receipt time. OpenAudioMC can create the next ride cue while
                    // the old channels are still fading; enumerating liveMedia after fade would
                    // incorrectly destroy that new cue (Ariel uses exactly this hand-off).
                    var destroyAllSnapshot = Array.from(liveMedia);
                    var destroyAllPolicyIds = Array.from(serverMediaPolicies.keys());
                    runAfterFade(function () {
                      destroyAllSnapshot.forEach(function (media) {
                        disposeMedia(media, 'server-destroy-all');
                      });
                      destroyAllPolicyIds.forEach(function (id) {
                        removePendingServerMedia(id);
                        serverMediaPolicies.delete(id);
                      });
                    });
                  } else {
                    var destroyedId = String(payload.soundId || '');
                    var target = mediaByServerId.get(destroyedId);
                    removePendingServerMedia(destroyedId);
                    serverMediaPolicies.delete(destroyedId);
                    if (target) {
                      runAfterFade(function () { disposeMedia(target, 'server-destroy'); });
                    } else {
                      // If source translation prevented exact ID association, let OAM remove its
                      // ended owner and use the ownership-removal path. A delayed global sweep can
                      // capture unrelated media created during the fade window.
                    }
                  }
                });
              }
              expose('__nra_handle_relay_data', inspectRelayLifecycle);

              var NativeWebSocket = window.WebSocket;
              if (NativeWebSocket) {
                try {
                  window.WebSocket = new Proxy(NativeWebSocket, {
                    construct: function (target, args, newTarget) {
                      var socket = Reflect.construct(target, args, newTarget);
                      socket.addEventListener('message', function (event) {
                        inspectRelayLifecycle(event.data);
                      });
                      socket.addEventListener('close', function () {
                        queueMicrotask(function () { disposeAllMedia('relay-disconnect'); });
                      });
                      return socket;
                    }
                  });
                } catch (e) {}
              }

              if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
                var nativeXHRSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                  this.addEventListener('load', function () {
                    try {
                      if (String(this.responseURL || '').indexOf('socket.io') >= 0) {
                        inspectRelayLifecycle(this.responseText);
                      }
                    } catch (e) {}
                  });
                  return nativeXHRSend.apply(this, arguments);
                };
              }
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
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
                      + ' muteSpeakers=' + !!media.muteSpeakers
                      + ' muteRegions=' + !!media.muteRegions
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

              if (window.__nra_add_relay_observer) {
                window.__nra_add_relay_observer(function (raw) { inspectRelay(raw, 'relay'); });
              }

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
                  window.__nra_add_tracked_media_listener(media, type, callbacks[type]);
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
        // Legacy-minimum is the controlled A/B mode: retain only the old WeakMap GainNode fix and
        // avoid all hooks into play/src/listeners/WebSocket. It intentionally cannot count
        // detached media; GPU footprint, prolonged telemetry loss, and process-generation
        // monitoring remain native and unaffected by those zero media counters.
        let legacyMinimumVolumeFix = WKUserScript(source: """
            (function () {
              if (window.__nra_volume_fix) return;
              window.__nra_volume_fix = true;
              window.__nra_audio_injection_mode = 'legacy-minimum';

              var elementGains = new WeakMap();
              var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
              if (desc && desc.set) {
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                  get: desc.get,
                  set: function (v) {
                    desc.set.call(this, v);
                    var gain = elementGains.get(this);
                    if (gain) gain.gain.value = v;
                  },
                  configurable: true,
                  enumerable: true
                });
              }

              if (typeof AudioContext !== 'undefined' || typeof webkitAudioContext !== 'undefined') {
                var AC = window.AudioContext || window.webkitAudioContext;
                var nativeCreateMediaElementSource = AC.prototype.createMediaElementSource;
                AC.prototype.createMediaElementSource = function (mediaEl) {
                  var sourceNode = nativeCreateMediaElementSource.call(this, mediaEl);
                  var nativeSourceDisconnect = sourceNode.disconnect.bind(sourceNode);
                  var volumeGain = this.createGain();
                  volumeGain.gain.value = mediaEl.volume;
                  elementGains.set(mediaEl, volumeGain);
                  sourceNode.connect(volumeGain);
                  sourceNode.connect = function () {
                    return volumeGain.connect.apply(volumeGain, arguments);
                  };
                  sourceNode.disconnect = function () {
                    return volumeGain.disconnect.apply(volumeGain, arguments);
                  };
                  try {
                    Object.defineProperty(sourceNode, '__nra_native_disconnect', {
                      value: nativeSourceDisconnect,
                      configurable: true
                    });
                  } catch (e) {}
                  return sourceNode;
                };
              }

              if (typeof webkitAudioContext !== 'undefined'
                  && window.webkitAudioContext !== window.AudioContext
                  && webkitAudioContext.prototype.createMediaElementSource) {
                var nativeCreateWebkitMediaElementSource =
                  webkitAudioContext.prototype.createMediaElementSource;
                webkitAudioContext.prototype.createMediaElementSource = function (mediaEl) {
                  var sourceNode = nativeCreateWebkitMediaElementSource.call(this, mediaEl);
                  var nativeSourceDisconnect = sourceNode.disconnect.bind(sourceNode);
                  var volumeGain = this.createGain();
                  volumeGain.gain.value = mediaEl.volume;
                  elementGains.set(mediaEl, volumeGain);
                  sourceNode.connect(volumeGain);
                  sourceNode.connect = function () {
                    return volumeGain.connect.apply(volumeGain, arguments);
                  };
                  sourceNode.disconnect = function () {
                    return volumeGain.disconnect.apply(volumeGain, arguments);
                  };
                  try {
                    Object.defineProperty(sourceNode, '__nra_native_disconnect', {
                      value: nativeSourceDisconnect,
                      configurable: true
                    });
                  } catch (e) {}
                  return sourceNode;
                };
              }
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)

        let legacyMinimumHealth = WKUserScript(source: """
            (function () {
              if (window.__nra_legacy_minimum_health) return;
              window.__nra_legacy_minimum_health = true;
              window.__nra_audio_injection_mode = 'legacy-minimum';
              window.__nra_media_health = function () {
                return {
                  mode: 'legacy-minimum',
                  created: 0,
                  disposed: 0,
                  live: 0,
                  lastSuccessfulPlayAt: 0,
                  lastEndedAt: 0,
                  recentErrorAbort: 0,
                  totalErrorAbort: 0,
                  audioContexts: window.__nra_audio_context_health
                    ? window.__nra_audio_context_health() : null
                };
              };
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)

        // Production-safe legacy path: keep the minimum volume shim unchanged and block only the
        // exact stale first-play continuation observed on Ariel. pause() merely records a bounded
        // candidate; teardown happens only if the same detached element immediately attempts its
        // very first native play. No relay packet, mute policy, source URL, or other media element
        // is used to make the decision.
        let legacyGuarded = WKUserScript(source: """
            (function () {
              if (window.__nra_legacy_guarded) return;
              window.__nra_legacy_guarded = true;
              window.__nra_audio_injection_mode = 'legacy-guarded';

              var STALE_WINDOW_MS = 100;
              var states = new WeakMap();
              var nextMediaId = 1;
              var staleCandidates = 0;
              var staleBlocked = 0;
              var staleDisposed = 0;
              var nativePlayForwarded = 0;
              var allowedFirstPlays = 0;
              var playResolved = 0;
              var playRejected = 0;
              var guardLiveCandidates = 0;
              var guardPendingTimers = 0;
              var lastSuccessfulPlayAt = 0;
              var lastBlockedAt = 0;
              var lastBlockedSourceHash = '0:00000000';
              var lastBlockedPauseAgeMs = -1;

              var nativeAddEventListener = HTMLMediaElement.prototype.addEventListener;
              var nativeRemoveEventListener = HTMLMediaElement.prototype.removeEventListener;
              var nativePlay = HTMLMediaElement.prototype.play;
              var nativePause = HTMLMediaElement.prototype.pause;
              var nativeLoad = HTMLMediaElement.prototype.load;

              function nowMs() {
                return typeof performance !== 'undefined' && performance.now
                  ? performance.now() : Date.now();
              }
              function hashText(value) {
                var text = String(value || '');
                var hash = 2166136261;
                for (var i = 0; i < text.length; i++) {
                  hash ^= text.charCodeAt(i);
                  hash = Math.imul(hash, 16777619);
                }
                return text.length + ':' + (hash >>> 0).toString(16).padStart(8, '0');
              }
              function currentSource(media) {
                return String(media.currentSrc || media.src
                  || (media.getAttribute && media.getAttribute('src')) || '');
              }
              function sourceRevision(media) {
                var attributeSource = media.getAttribute
                  ? String(media.getAttribute('src') || '') : '';
                return JSON.stringify([
                  attributeSource,
                  String(media.src || ''),
                  String(media.currentSrc || '')
                ]);
              }
              function captureOf(options) {
                return options === true || !!(options && typeof options === 'object'
                  && options.capture);
              }
              function isTerminalType(type) {
                return type === 'ended' || type === 'emptied' || type === 'error'
                  || type === 'abort';
              }
              function stateFor(media) {
                var state = states.get(media);
                if (!state) {
                  state = {
                    id: nextMediaId++,
                    disposed: false,
                    successfulPlay: false,
                    nativePlayAttempts: 0,
                    playInFlight: false,
                    candidateAt: -1,
                    candidateSource: '',
                    candidateSourceRevision: '',
                    candidateToken: 0,
                    candidateTimer: null,
                    terminalListeners: [],
                    graphs: new Set()
                  };
                  states.set(media, state);
                }
                return state;
              }
              function emit(kind, details) {
                try {
                  window.webkit.messageHandlers.nativeLog.postMessage({
                    level: kind === 'STALE_FIRST_PLAY_BLOCKED' ? 'warn' : 'observe',
                    message: '[IMF-AUDIO-GUARD] ' + kind + ' ' + JSON.stringify(details || {})
                  });
                } catch (e) {}
              }
              function clearCandidate(state) {
                if (state.candidateToken !== 0) {
                  state.candidateToken = 0;
                  state.candidateAt = -1;
                  state.candidateSource = '';
                  state.candidateSourceRevision = '';
                  guardLiveCandidates = Math.max(0, guardLiveCandidates - 1);
                }
                if (state.candidateTimer !== null) {
                  clearTimeout(state.candidateTimer);
                  state.candidateTimer = null;
                  guardPendingTimers = Math.max(0, guardPendingTimers - 1);
                }
              }
              function recordCandidate(media, state) {
                clearCandidate(state);
                staleCandidates++;
                guardLiveCandidates++;
                state.candidateAt = nowMs();
                state.candidateSource = currentSource(media);
                state.candidateSourceRevision = sourceRevision(media);
                var token = staleCandidates;
                state.candidateToken = token;
                guardPendingTimers++;
                state.candidateTimer = setTimeout(function () {
                  state.candidateTimer = null;
                  guardPendingTimers = Math.max(0, guardPendingTimers - 1);
                  if (state.candidateToken === token) {
                    state.candidateToken = 0;
                    state.candidateAt = -1;
                    state.candidateSource = '';
                    state.candidateSourceRevision = '';
                    guardLiveCandidates = Math.max(0, guardLiveCandidates - 1);
                  }
                }, STALE_WINDOW_MS);
                emit('STALE_FIRST_PLAY_CANDIDATE', {
                  media: state.id,
                  srcHash: hashText(state.candidateSource)
                });
              }
              function endedOwnerCount(state) {
                var count = 0;
                state.terminalListeners.forEach(function (record) {
                  if (record.type === 'ended') count++;
                });
                return count;
              }
              function removeTrackedListeners(media, state) {
                state.terminalListeners.forEach(function (record) {
                  try {
                    nativeRemoveEventListener.call(
                      media, record.type, record.listener, record.capture);
                  } catch (e) {}
                });
                state.terminalListeners.length = 0;
                ['ended', 'emptied', 'error', 'abort'].forEach(function (type) {
                  try { media['on' + type] = null; } catch (e) {}
                });
              }
              function disconnectGraphs(state) {
                state.graphs.forEach(function (graph) {
                  try { graph.disconnectVolume(); } catch (e) {}
                  try { graph.disconnectNative(); } catch (e) {}
                });
                state.graphs.clear();
              }
              function disposeStaleMedia(media, state, pauseAgeMs) {
                if (state.disposed) return false;
                var sourceHash = hashText(currentSource(media));
                state.disposed = true;
                state.playInFlight = false;
                clearCandidate(state);
                removeTrackedListeners(media, state);
                disconnectGraphs(state);
                try { nativePause.call(media); } catch (e) {}
                try {
                  if ('srcObject' in media && media.srcObject != null) media.srcObject = null;
                } catch (e) {}
                try { media.removeAttribute('src'); } catch (e) {}
                try { nativeLoad.call(media); } catch (e) {}
                staleDisposed++;
                emit('STALE_MEDIA_DISPOSED', {
                  media: state.id,
                  srcHash: sourceHash,
                  pauseAgeMs: Math.round(pauseAgeMs)
                });
                return true;
              }
              function candidatePauseEligible(media, state) {
                return !state.disposed && !state.successfulPlay
                  && !state.playInFlight
                  && media.tagName === 'AUDIO'
                  && !document.contains(media)
                  && !!currentSource(media)
                  && !media.srcObject
                  && !!media.paused
                  && Number(media.currentTime || 0) <= 0.001
                  && endedOwnerCount(state) > 0;
              }
              function stalePlayAge(media, state) {
                if (state.disposed || state.successfulPlay || state.playInFlight
                    || state.candidateToken === 0 || media.tagName !== 'AUDIO'
                    || document.contains(media) || !currentSource(media) || media.srcObject
                    || Number(media.currentTime || 0) > 0.001
                    || endedOwnerCount(state) === 0
                    || sourceRevision(media) !== state.candidateSourceRevision) return -1;
                var age = nowMs() - state.candidateAt;
                return age >= 0 && age <= STALE_WINDOW_MS ? age : -1;
              }

              HTMLMediaElement.prototype.addEventListener = function (type, listener, options) {
                if (listener && isTerminalType(type)) {
                  var state = stateFor(this);
                  var capture = captureOf(options);
                  var exists = state.terminalListeners.some(function (record) {
                    return record.type === type && record.listener === listener
                      && record.capture === capture;
                  });
                  if (!exists) {
                    state.terminalListeners.push({
                      type: type,
                      listener: listener,
                      options: options,
                      capture: capture
                    });
                  }
                }
                return nativeAddEventListener.call(this, type, listener, options);
              };
              HTMLMediaElement.prototype.removeEventListener = function (type, listener, options) {
                var state = states.get(this);
                if (state && listener && isTerminalType(type)) {
                  var capture = captureOf(options);
                  state.terminalListeners = state.terminalListeners.filter(function (record) {
                    return record.type !== type || record.listener !== listener
                      || record.capture !== capture;
                  });
                }
                return nativeRemoveEventListener.call(this, type, listener, options);
              };
              HTMLMediaElement.prototype.pause = function () {
                var state = stateFor(this);
                if (candidatePauseEligible(this, state)) recordCandidate(this, state);
                return nativePause.apply(this, arguments);
              };
              HTMLMediaElement.prototype.play = function () {
                var media = this;
                var state = stateFor(media);
                var age = stalePlayAge(media, state);
                if (age >= 0) {
                  staleBlocked++;
                  lastBlockedAt = Date.now();
                  lastBlockedSourceHash = hashText(currentSource(media));
                  lastBlockedPauseAgeMs = Math.round(age);
                  emit('STALE_FIRST_PLAY_BLOCKED', {
                    media: state.id,
                    srcHash: lastBlockedSourceHash,
                    pauseAgeMs: lastBlockedPauseAgeMs,
                    nativePlayCalled: false
                  });
                  disposeStaleMedia(media, state, age);
                  var rejected = Promise.reject(new DOMException(
                    'Stopped detached media cannot start its first native playback',
                    'AbortError'));
                  rejected.catch(function () { playRejected++; });
                  return rejected;
                }

                clearCandidate(state);
                if (state.disposed) {
                  playRejected++;
                  return Promise.reject(new DOMException(
                    'Disposed media cannot be replayed', 'AbortError'));
                }
                state.nativePlayAttempts++;
                state.playInFlight = true;
                nativePlayForwarded++;
                var result;
                try {
                  result = nativePlay.apply(media, arguments);
                } catch (error) {
                  state.playInFlight = false;
                  playRejected++;
                  throw error;
                }
                if (result && typeof result.then === 'function') {
                  result.then(function () {
                    state.playInFlight = false;
                    if (!state.successfulPlay) allowedFirstPlays++;
                    state.successfulPlay = true;
                    playResolved++;
                    lastSuccessfulPlayAt = Date.now();
                  }, function () {
                    state.playInFlight = false;
                    playRejected++;
                  });
                } else {
                  state.playInFlight = false;
                  if (!state.successfulPlay) allowedFirstPlays++;
                  state.successfulPlay = true;
                  playResolved++;
                  lastSuccessfulPlayAt = Date.now();
                }
                return result;
              };

              function observeMediaElementSource(AC) {
                if (!AC || !AC.prototype || !AC.prototype.createMediaElementSource) return;
                var wrappedCreate = AC.prototype.createMediaElementSource;
                AC.prototype.createMediaElementSource = function (media) {
                  var sourceNode = wrappedCreate.call(this, media);
                  var state = stateFor(media);
                  var disconnectVolume = sourceNode.disconnect.bind(sourceNode);
                  var disconnectNative = typeof sourceNode.__nra_native_disconnect === 'function'
                    ? sourceNode.__nra_native_disconnect : function () {};
                  if (state.disposed) {
                    try { disconnectVolume(); } catch (e) {}
                    try { disconnectNative(); } catch (e) {}
                  } else {
                    state.graphs.add({
                      disconnectVolume: disconnectVolume,
                      disconnectNative: disconnectNative
                    });
                  }
                  return sourceNode;
                };
              }
              var primaryAC = window.AudioContext || window.webkitAudioContext;
              observeMediaElementSource(primaryAC);
              if (window.webkitAudioContext && window.webkitAudioContext !== primaryAC) {
                observeMediaElementSource(window.webkitAudioContext);
              }

              window.__nra_media_health = function () {
                return {
                  mode: 'legacy-guarded',
                  created: 0,
                  disposed: 0,
                  live: 0,
                  lastSuccessfulPlayAt: lastSuccessfulPlayAt,
                  lastEndedAt: 0,
                  recentErrorAbort: 0,
                  totalErrorAbort: 0,
                  staleCandidates: staleCandidates,
                  staleBlocked: staleBlocked,
                  staleDisposed: staleDisposed,
                  nativePlayForwarded: nativePlayForwarded,
                  allowedFirstPlays: allowedFirstPlays,
                  playAttempts: nativePlayForwarded + staleBlocked,
                  playResolved: playResolved,
                  playRejected: playRejected,
                  playPending: 0,
                  stalePlayRejected: staleBlocked,
                  guardLiveCandidates: guardLiveCandidates,
                  guardPendingTimers: guardPendingTimers,
                  lastBlockedAt: lastBlockedAt,
                  lastBlockedSourceHash: lastBlockedSourceHash,
                  lastBlockedPauseAgeMs: lastBlockedPauseAgeMs,
                  audioContexts: window.__nra_audio_context_health
                    ? window.__nra_audio_context_health() : null
                };
              };
              emit('GUARD_INSTALLED', {windowMs: STALE_WINDOW_MS});
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)

        // Legacy-observe preserves the legacy-minimum playback path exactly: it never changes a
        // relay packet, rejects a play, tears down media, or applies speaker muting. It only records
        // bounded, redacted lifecycle evidence. WeakMaps and self-removing terminal listeners keep
        // detached elements observable without introducing a permanent strong media registry.
        let legacyObserve = WKUserScript(source: """
            (function () {
              if (window.__nra_legacy_observe) return;
              window.__nra_legacy_observe = true;
              window.__nra_audio_injection_mode = 'legacy-observe';

              var MAX_EVENTS = 512;
              var events = [];
              var dropped = 0;
              var sequence = 0;
              var startedAt = performance.now();
              var mediaIds = new WeakMap();
              var handlerStates = new WeakMap();
              var observerCallbacks = new WeakSet();
              var terminalCallbacks = new WeakMap();
              var sourceNodeIds = new WeakMap();
              var nextMediaId = 1;
              var nextSourceNodeId = 1;

              function hashText(value) {
                var text = String(value || '');
                var hash = 2166136261;
                for (var i = 0; i < text.length; i++) {
                  hash ^= text.charCodeAt(i);
                  hash = Math.imul(hash, 16777619);
                }
                return text.length + ':' + (hash >>> 0).toString(16).padStart(8, '0');
              }
              function numberOrNull(value) {
                return typeof value === 'number' && isFinite(value)
                  ? Math.round(value * 1000) / 1000 : null;
              }
              function boundedIdentifier(value) {
                var text = String(value == null ? '-' : value);
                if (/^[A-Za-z0-9_.:-]{1,64}$/.test(text)) return text;
                return 'hash:' + hashText(text);
              }
              function emit(kind, details) {
                var record = {
                  seq: ++sequence,
                  atMs: Math.round(performance.now() - startedAt),
                  epochMs: Date.now(),
                  kind: kind,
                  details: details || {}
                };
                if (events.length >= MAX_EVENTS) {
                  events.shift();
                  dropped++;
                }
                events.push(record);
                try {
                  window.webkit.messageHandlers.nativeLog.postMessage({
                    level: 'observe',
                    message: '[IMF-AUDIO-OBSERVE +' + record.atMs + 'ms #' + record.seq
                      + ' epochMs=' + record.epochMs + '] '
                      + kind + ' ' + JSON.stringify(record.details)
                  });
                } catch (e) {}
                return record;
              }
              function snapshot() {
                return {
                  mode: 'legacy-observe',
                  capacity: MAX_EVENTS,
                  count: events.length,
                  dropped: dropped,
                  lastSequence: sequence,
                  events: events.slice()
                };
              }
              window.__nra_observe_snapshot = snapshot;
              window.__nra_observe_clear = function () {
                events.length = 0;
                dropped = 0;
                return snapshot();
              };
              function mediaId(media) {
                var id = mediaIds.get(media);
                if (!id) {
                  id = nextMediaId++;
                  mediaIds.set(media, id);
                  emit('MEDIA_OBSERVED', {media: id, tag: String(media.tagName || 'MEDIA')});
                }
                return id;
              }
              function mediaDetails(media) {
                var handlers = handlerStates.get(media);
                return {
                  media: mediaId(media),
                  srcHash: hashText(media.currentSrc || media.src
                    || (media.getAttribute && media.getAttribute('src')) || ''),
                  currentTime: numberOrNull(media.currentTime),
                  duration: numberOrNull(media.duration),
                  volume: numberOrNull(media.volume),
                  muted: !!media.muted,
                  paused: !!media.paused,
                  detached: !document.contains(media),
                  endedHandlers: handlers ? handlers.count : 0
                };
              }

              // Observe page ownership without retaining page callbacks strongly.
              var nativeAddEventListener = HTMLMediaElement.prototype.addEventListener;
              var nativeRemoveEventListener = HTMLMediaElement.prototype.removeEventListener;
              HTMLMediaElement.prototype.addEventListener = function (type, listener, options) {
                if (type === 'ended' && listener && !observerCallbacks.has(listener)) {
                  var state = handlerStates.get(this);
                  if (!state) {
                    state = {listeners: new WeakSet(), count: 0};
                    handlerStates.set(this, state);
                  }
                  if ((typeof listener === 'function' || typeof listener === 'object')
                      && !state.listeners.has(listener)) {
                    state.listeners.add(listener);
                    state.count++;
                    emit('ENDED_HANDLER_ADD', mediaDetails(this));
                  }
                }
                return nativeAddEventListener.call(this, type, listener, options);
              };
              HTMLMediaElement.prototype.removeEventListener = function (type, listener, options) {
                if (type === 'ended' && listener && !observerCallbacks.has(listener)) {
                  var state = handlerStates.get(this);
                  if (state && state.listeners.has(listener)) {
                    state.listeners.delete(listener);
                    state.count = Math.max(0, state.count - 1);
                    emit('ENDED_HANDLER_REMOVE', mediaDetails(this));
                  }
                }
                return nativeRemoveEventListener.call(this, type, listener, options);
              };

              function removeTerminalObservers(media) {
                var callbacks = terminalCallbacks.get(media);
                if (!callbacks) return;
                Object.keys(callbacks).forEach(function (type) {
                  nativeRemoveEventListener.call(media, type, callbacks[type]);
                });
                terminalCallbacks.delete(media);
              }
              function makeTerminalObserver(type) {
                var callback = function (event) {
                  var target = event && (event.currentTarget || event.target);
                  if (target) emit('MEDIA_' + type.toUpperCase(), mediaDetails(target));
                  if (target) removeTerminalObservers(target);
                };
                observerCallbacks.add(callback);
                return callback;
              }
              function attachTerminalObservers(media) {
                if (terminalCallbacks.has(media)) return;
                var callbacks = {};
                ['ended', 'emptied', 'error', 'abort'].forEach(function (type) {
                  var callback = makeTerminalObserver(type);
                  callbacks[type] = callback;
                  nativeAddEventListener.call(media, type, callback);
                });
                terminalCallbacks.set(media, callbacks);
              }

              function observePlayResult(result, weakMedia, id) {
                result.then(function () {
                  var current = weakMedia && weakMedia.deref();
                  emit('PLAY_RESOLVED', current ? mediaDetails(current) : {media: id});
                }, function (error) {
                  var current = weakMedia && weakMedia.deref();
                  var details = current ? mediaDetails(current) : {media: id};
                  details.error = boundedIdentifier(error && error.name);
                  emit('PLAY_REJECTED', details);
                });
              }

              var nativePlay = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                var media = this;
                attachTerminalObservers(media);
                var id = mediaId(media);
                emit('PLAY_CALL', mediaDetails(media));
                var result;
                try {
                  result = nativePlay.apply(media, arguments);
                } catch (error) {
                  emit('PLAY_THROW', {media: id, error: boundedIdentifier(error && error.name)});
                  throw error;
                }
                if (result && typeof result.then === 'function') {
                  var weakMedia = typeof WeakRef === 'function' ? new WeakRef(media) : null;
                  observePlayResult(result, weakMedia, id);
                }
                return result;
              };
              var nativePause = HTMLMediaElement.prototype.pause;
              HTMLMediaElement.prototype.pause = function () {
                emit('PAUSE_CALL', mediaDetails(this));
                return nativePause.apply(this, arguments);
              };

              // Inspect already-parsed Socket.IO envelopes. The original parser runs first and
              // its exact return object is handed back unchanged.
              function inspectPacket(packet) {
                if (!Array.isArray(packet) || packet[0] !== 'data' || !packet[1]) return;
                var envelope = packet[1];
                var type = String(envelope.type || '').split('.').pop();
                var payload = envelope.payload || {};
                if (type === 'ClientCreateMediaPayload') {
                  var media = payload.media || {};
                  var startTime = Date.parse(media.startInstant || '');
                  emit('RELAY_CREATE', {
                    id: boundedIdentifier(media.mediaId),
                    srcHash: hashText(media.source),
                    loop: !!media.loop,
                    pickup: !!media.doPickup,
                    volume: numberOrNull(media.volume),
                    muteSpeakers: !!media.muteSpeakers,
                    muteRegions: !!media.muteRegions,
                    offsetMs: numberOrNull(media.startAtMillis),
                    startAgeMs: isFinite(startTime) ? Math.round(Date.now() - startTime) : null,
                    fadeMs: numberOrNull(media.fadeTime)
                  });
                } else if (type === 'ClientDestroyMediaPayload') {
                  emit('RELAY_DESTROY', {
                    id: boundedIdentifier(payload.soundId),
                    all: !!payload.all,
                    fadeMs: numberOrNull(payload.fadeTime)
                  });
                } else if (type === 'ClientUpdateMediaPayload') {
                  var options = payload.mediaOptions || {};
                  emit('RELAY_UPDATE', {
                    id: boundedIdentifier(options.target),
                    volume: numberOrNull(options.volume),
                    speed: numberOrNull(options.speed),
                    fadeMs: numberOrNull(options.fadeTimeMs)
                  });
                } else if (type === 'ClientVolumePayload') {
                  emit('RELAY_MASTER_VOLUME', {volume: numberOrNull(payload.volume)});
                }
              }
              var nativeJSONParse = JSON.parse;
              JSON.parse = function () {
                var parsed = nativeJSONParse.apply(this, arguments);
                try { inspectPacket(parsed); } catch (e) {}
                return parsed;
              };

              function observeMediaElementSource(AC) {
                if (!AC || !AC.prototype || !AC.prototype.createMediaElementSource) return;
                var nativeCreate = AC.prototype.createMediaElementSource;
                AC.prototype.createMediaElementSource = function (media) {
                  var sourceNode = nativeCreate.call(this, media);
                  var nodeId = nextSourceNodeId++;
                  var linkedMediaId = mediaId(media);
                  var linkedSourceHash = mediaDetails(media).srcHash;
                  sourceNodeIds.set(sourceNode, nodeId);
                  emit('GRAPH_CREATE', {
                    node: nodeId, media: linkedMediaId, srcHash: linkedSourceHash
                  });
                  wrapSourceNode(sourceNode, nodeId, linkedMediaId);
                  return sourceNode;
                };
              }
              function wrapSourceNode(sourceNode, nodeId, linkedMediaId) {
                  var nativeConnect = sourceNode.connect;
                  var nativeDisconnect = sourceNode.disconnect;
                  sourceNode.connect = function () {
                    emit('GRAPH_CONNECT', {node: nodeId, media: linkedMediaId});
                    return nativeConnect.apply(this, arguments);
                  };
                  sourceNode.disconnect = function () {
                    emit('GRAPH_DISCONNECT', {node: nodeId, media: linkedMediaId});
                    return nativeDisconnect.apply(this, arguments);
                  };
              }
              var primaryAC = window.AudioContext || window.webkitAudioContext;
              observeMediaElementSource(primaryAC);
              if (window.webkitAudioContext && window.webkitAudioContext !== primaryAC) {
                observeMediaElementSource(window.webkitAudioContext);
              }

              window.__nra_media_health = function () {
                return {
                  mode: 'legacy-observe',
                  created: 0,
                  disposed: 0,
                  live: 0,
                  lastSuccessfulPlayAt: 0,
                  lastEndedAt: 0,
                  recentErrorAbort: 0,
                  totalErrorAbort: 0,
                  observeEvents: events.length,
                  observeDropped: dropped,
                  observeLastSequence: sequence,
                  observedMedia: nextMediaId - 1,
                  observedSourceNodes: nextSourceNodeId - 1,
                  audioContexts: window.__nra_audio_context_health
                    ? window.__nra_audio_context_health() : null
                };
              };
              emit('OBSERVE_INSTALLED', {capacity: MAX_EVENTS});
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)

        switch audioInjectionMode {
        case .legacyMinimum:
            config.userContentController.addUserScript(legacyMinimumVolumeFix)
            config.userContentController.addUserScript(legacyMinimumHealth)
        case .legacyObserve:
            config.userContentController.addUserScript(legacyMinimumVolumeFix)
            config.userContentController.addUserScript(legacyObserve)
        case .legacyGuarded:
            config.userContentController.addUserScript(legacyMinimumVolumeFix)
            config.userContentController.addUserScript(legacyGuarded)
        case .managedLifecycle:
            config.userContentController.addUserScript(audioVolumeFix)
            config.userContentController.addUserScript(staleMediaPlayGuard)
            if audioDiagnosticsEnabled {
                config.userContentController.addUserScript(audioDiagnostics)
            }
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
        GPUWatchdog = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.pollGPUProcess()
        }
    }

    private func pollGPUProcess() {
        guard !destroyed else { return }
        let current = webKitGPUProcessIDs()
        if let observed = observedGPUProcessID {
            if !current.contains(observed) {
                let replacement = current.subtracting(GPUProcessIDsBeforeWebView).max()
                writeLine(jsonLine([
                    "type": "gpu_process_changed",
                    "oldPid": observed,
                    "newPid": replacement ?? -1
                ]))
                GPUWatchdog?.invalidate()
                GPUWatchdog = nil
                shutdown { NSApplication.shared.terminate(nil) }
                return
            }
            pollGPUFootprint(observed)
            return
        }
        if let candidate = current.subtracting(GPUProcessIDsBeforeWebView).max() {
            observedGPUProcessID = candidate
            GPUFootprintPolicyState.reset()
            GPUFootprintAvailabilityState.reset()
            GPUFootprintReadFailures = 0
            lastGPUFootprintTelemetryAtMs = 0
            writeLine(jsonLine(["type": "gpu_process_observed", "pid": candidate]))
            pollGPUFootprint(candidate)
        }
    }

    private func pollGPUFootprint(_ pid: Int32) {
        guard let memory = GPUProcessMemoryForPID(pid) else {
            GPUFootprintReadFailures += 1
            let nowMs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            let availability = GPUFootprintAvailabilityState.observeUnavailable(timestampMs: nowMs)
            if GPUFootprintReadFailures == 3 || GPUFootprintReadFailures % 30 == 0
                || availability.shouldRecycle {
                writeLine(jsonLine([
                    "type": "gpu_memory_unavailable",
                    "pid": pid,
                    "consecutiveFailures": GPUFootprintReadFailures,
                    "hasSuccessfulSample": availability.hasSuccessfulSample,
                    "unavailableDurationMs": availability.unavailableDurationMs,
                    "recycleAfterMs": GPUFootprintAvailabilityState.unavailableLimitMs
                ]))
            }
            if availability.shouldRecycle {
                triggerGPUFootprintMonitorFailure(pid: pid, evaluation: availability)
            }
            return
        }
        GPUFootprintReadFailures = 0
        let nowMs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        GPUFootprintAvailabilityState.observeAvailable(timestampMs: nowMs)
        let evaluation = GPUFootprintPolicyState.observe(
            timestampMs: nowMs,
            footprintBytes: memory.physicalFootprintBytes)
        emitGPUFootprintTelemetry(
            pid: pid, memory: memory, evaluation: evaluation, timestampMs: nowMs,
            force: evaluation.reason != nil)
        if let reason = evaluation.reason {
            triggerGPUMemoryPressure(
                pid: pid, memory: memory, evaluation: evaluation, reason: reason)
        }
    }

    private func triggerGPUFootprintMonitorFailure(
        pid: Int32,
        evaluation: GPUFootprintAvailabilityEvaluation
    ) {
        GPUWatchdog?.invalidate()
        GPUWatchdog = nil
        writeLine(jsonLine([
            "type": "gpu_memory_monitor_failed",
            "pid": pid,
            "consecutiveFailures": GPUFootprintReadFailures,
            "unavailableDurationMs": evaluation.unavailableDurationMs,
            "recycleAfterMs": GPUFootprintAvailabilityState.unavailableLimitMs
        ]))
        shutdown { NSApplication.shared.terminate(nil) }
    }

    private func emitGPUFootprintTelemetry(
        pid: Int32,
        memory: GPUProcessMemory,
        evaluation: GPUFootprintEvaluation,
        timestampMs: Int64,
        force: Bool
    ) {
        guard force || lastGPUFootprintTelemetryAtMs == 0
            || timestampMs - lastGPUFootprintTelemetryAtMs >= GPUFootprintTelemetryIntervalMs
        else { return }
        lastGPUFootprintTelemetryAtMs = timestampMs
        let thresholds = GPUFootprintPolicyState.thresholds
        writeLine(jsonLine([
            "type": "gpu_memory_health",
            "pid": pid,
            "physicalFootprintBytes": NSNumber(value: memory.physicalFootprintBytes),
            "residentBytes": NSNumber(value: memory.residentBytes),
            "lifetimeMaxFootprintBytes": NSNumber(value: memory.lifetimeMaxFootprintBytes),
            "windowDeltaBytes": NSNumber(value: evaluation.windowDeltaBytes),
            "windowDurationMs": evaluation.windowDurationMs,
            "absoluteBreachSamples": evaluation.absoluteBreachSamples,
            "absoluteLimitBytes": NSNumber(value: thresholds.absoluteBytes),
            "rapidGrowthLimitBytes": NSNumber(value: thresholds.rapidGrowthBytes),
            "rapidGrowthFloorBytes": NSNumber(value: thresholds.rapidGrowthFloorBytes),
            "rapidGrowthWindowMs": thresholds.rapidGrowthWindowMs
        ]))
    }

    private func triggerGPUMemoryPressure(
        pid: Int32,
        memory: GPUProcessMemory,
        evaluation: GPUFootprintEvaluation,
        reason: String
    ) {
        guard pendingGPUMemoryPressurePayload == nil else { return }
        GPUWatchdog?.invalidate()
        GPUWatchdog = nil
        pendingGPUMemoryPressurePayload = [
            "type": "gpu_memory_pressure",
            "reason": reason,
            "pid": pid,
            "physicalFootprintBytes": NSNumber(value: memory.physicalFootprintBytes),
            "residentBytes": NSNumber(value: memory.residentBytes),
            "lifetimeMaxFootprintBytes": NSNumber(value: memory.lifetimeMaxFootprintBytes),
            "windowDeltaBytes": NSNumber(value: evaluation.windowDeltaBytes),
            "windowDurationMs": evaluation.windowDurationMs,
            "absoluteBreachSamples": evaluation.absoluteBreachSamples
        ]

        // Give the page 250 ms to return a bounded anonymous snapshot. The deadline is deliberate:
        // a wedged WebContent process must never delay tearing down a rapidly growing GPU process.
        webView.evaluateJavaScript(
            "window.__nra_media_snapshot ? window.__nra_media_snapshot(24) : []"
        ) { [weak self] result, _ in
            self?.finishGPUMemoryPressure(snapshot: result)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
            self?.finishGPUMemoryPressure(snapshot: nil)
        }
    }

    private func finishGPUMemoryPressure(snapshot: Any?) {
        guard var payload = pendingGPUMemoryPressurePayload else { return }
        pendingGPUMemoryPressurePayload = nil
        payload["mediaSnapshot"] = snapshot ?? []
        writeLine(jsonLine(payload))
        shutdown { NSApplication.shared.terminate(nil) }
    }

    private func installPreferredVolumeScript(_ volume: Int) {
        guard !preferredVolumeScriptInstalled, (0...100).contains(volume) else { return }
        preferredVolumeScriptInstalled = true
        let preferredVolumeBootstrap = WKUserScript(source: """
            (function() {
              var preferredVolume = \(volume);
              window.__nra_preferred_volume = preferredVolume;
              window.__nra_master_volume_percent = preferredVolume;
              window.__nra_volume_gate_active = true;
              window.__nra_preferred_volume_applied = false;

              var observer = null;
              var timeout = null;
              function stopWatching() {
                if (observer) observer.disconnect();
                observer = null;
                if (timeout) clearTimeout(timeout);
                timeout = null;
              }
              function releaseGate() {
                if (window.__nra_commit_master_volume) {
                  window.__nra_commit_master_volume(preferredVolume);
                } else {
                  window.__nra_volume_gate_active = false;
                }
              }
              function applyPreferredVolume() {
                var rangeInput = document.querySelector('input[type="range"]');
                if (!rangeInput) return false;
                var descriptor = Object.getOwnPropertyDescriptor(
                  window.HTMLInputElement.prototype, 'value');
                if (!descriptor || !descriptor.set) return false;
                descriptor.set.call(rangeInput, preferredVolume);
                rangeInput.dispatchEvent(new Event('input', { bubbles: true }));
                rangeInput.dispatchEvent(new Event('change', { bubbles: true }));
                if (parseInt(rangeInput.value) !== preferredVolume) return false;
                window.__nra_preferred_volume_applied = true;
                stopWatching();
                // Keep the gate closed through React's event/state flush. In particular, volume 0
                // must reach existing MediaElementSource gains before any queued play can be heard.
                setTimeout(releaseGate, 50);
                return true;
              }

              window.__nra_apply_preferred_volume = applyPreferredVolume;
              observer = new MutationObserver(applyPreferredVolume);
              observer.observe(document, { childList: true, subtree: true });
              timeout = setTimeout(stopWatching, 60000);
              applyPreferredVolume();
            })();
            """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        webView.configuration.userContentController.addUserScript(preferredVolumeBootstrap)
    }

    func loadURL(_ urlString: String, preferredVolume: Int? = nil) {
        guard let url = URL(string: urlString) else {
            writeLine(jsonLine(["type": "error", "message": "Invalid OpenAudioMC session URL"]))
            return
        }
        if let preferredVolume {
            installPreferredVolumeScript(preferredVolume)
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
        GPUWatchdog?.invalidate()
        GPUWatchdog = nil
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
        writeLine(jsonLine(["type": "loaded", "success": true]))
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        let details = error as NSError
        writeLine(jsonLine(["type": "error",
                            "message": "Navigation failed (\(details.domain):\(details.code))"]))
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let details = error as NSError
        writeLine(jsonLine(["type": "error",
                            "message": "Load failed (\(details.domain):\(details.code))"]))
    }

    // WebKit's content-process (com.apple.WebKit.WebContent) crashed. Audio stops,
    // the WKWebView remains alive but blank, and the only way to recover is to
    // reload the URL. Emit a dedicated signal so the Java side can distinguish
    // this from a server-initiated session end (which has identical user-visible
    // symptoms: audio stops, slider disappears) and record it for /oa disconnects.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        writeLine(jsonLine(["type": "web_content_terminated"]))
        shutdown { NSApplication.shared.terminate(nil) }
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
                    let preferredVolume = (json["preferredVolume"] as? NSNumber)?.intValue
                    DispatchQueue.main.async {
                        self.manager.loadURL(url, preferredVolume: preferredVolume)
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

        writeLine(jsonLine([
            "type": "ready",
            "audioInjectionMode": manager.audioInjectionMode.rawValue
        ]))
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

func runGPUFootprintPolicySelfTest() -> Bool {
    var absolutePolicy = GPUFootprintPolicy(thresholds: GPUFootprintThresholds(
        absoluteBytes: 100,
        rapidGrowthBytes: 1_000,
        rapidGrowthFloorBytes: 0,
        rapidGrowthWindowMs: 1_000,
        absoluteConsecutiveSamples: 2))
    let absoluteFirst = absolutePolicy.observe(timestampMs: 0, footprintBytes: 120)
    let absoluteSecond = absolutePolicy.observe(timestampMs: 100, footprintBytes: 130)

    var growthPolicy = GPUFootprintPolicy(thresholds: GPUFootprintThresholds(
        absoluteBytes: 1_000,
        rapidGrowthBytes: 80,
        rapidGrowthFloorBytes: 150,
        rapidGrowthWindowMs: 1_000,
        absoluteConsecutiveSamples: 2))
    let growthFirst = growthPolicy.observe(timestampMs: 0, footprintBytes: 100)
    let growthSecond = growthPolicy.observe(timestampMs: 500, footprintBytes: 190)

    var recoveryPolicy = GPUFootprintPolicy(thresholds: GPUFootprintThresholds(
        absoluteBytes: 100,
        rapidGrowthBytes: 1_000,
        rapidGrowthFloorBytes: 0,
        rapidGrowthWindowMs: 1_000,
        absoluteConsecutiveSamples: 2))
    _ = recoveryPolicy.observe(timestampMs: 0, footprintBytes: 120)
    _ = recoveryPolicy.observe(timestampMs: 100, footprintBytes: 80)
    let recovered = recoveryPolicy.observe(timestampMs: 200, footprintBytes: 120)

    var availabilityPolicy = GPUFootprintAvailabilityPolicy(unavailableLimitMs: 100)
    let neverAvailable = availabilityPolicy.observeUnavailable(timestampMs: 1_000)
    availabilityPolicy.observeAvailable(timestampMs: 1_000)
    let transientUnavailable = availabilityPolicy.observeUnavailable(timestampMs: 1_099)
    let prolongedUnavailable = availabilityPolicy.observeUnavailable(timestampMs: 1_100)
    availabilityPolicy.observeAvailable(timestampMs: 1_101)
    let recoveredAvailability = availabilityPolicy.observeUnavailable(timestampMs: 1_150)

    return absoluteFirst.reason == nil
        && absoluteSecond.reason == "absolute-footprint"
        && growthFirst.reason == nil
        && growthSecond.reason == "rapid-growth"
        && growthSecond.windowDeltaBytes == 90
        && recovered.reason == nil
        && recovered.absoluteBreachSamples == 1
        && !neverAvailable.hasSuccessfulSample
        && !neverAvailable.shouldRecycle
        && transientUnavailable.hasSuccessfulSample
        && transientUnavailable.unavailableDurationMs == 99
        && !transientUnavailable.shouldRecycle
        && prolongedUnavailable.shouldRecycle
        && recoveredAvailability.unavailableDurationMs == 49
        && !recoveredAvailability.shouldRecycle
}

if CommandLine.arguments.contains("--self-test-gpu-memory-policy") {
    let success = runGPUFootprintPolicySelfTest()
    writeLine(jsonLine(["type": "gpu_memory_policy_self_test", "success": success]))
    exit(success ? EXIT_SUCCESS : EXIT_FAILURE)
}

if CommandLine.arguments.contains("--print-audio-injection-mode") {
    writeLine(jsonLine([
        "type": "audio_injection_mode",
        "mode": AudioInjectionMode.selected().rawValue
    ]))
    exit(EXIT_SUCCESS)
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)  // No dock icon, no menu bar
let delegate = AppDelegate()
app.delegate = delegate
app.run()
