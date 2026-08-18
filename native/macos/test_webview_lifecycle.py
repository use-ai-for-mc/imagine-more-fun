#!/usr/bin/env python3
"""Real WKWebView/CoreMedia stress and helper-generation recovery test for macOS."""

from __future__ import annotations

import base64
import json
import math
import os
import re
import selectors
import signal
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "src/main/resources/native/macos/webview-helper"
GPU_PATTERN = "/XPCServices/com.apple.WebKit.GPU.xpc/Contents/MacOS/com.apple.WebKit.GPU"


def gpu_pids() -> set[int]:
    result = subprocess.run(
        ["/usr/bin/pgrep", "-f", GPU_PATTERN],
        check=False,
        capture_output=True,
        text=True,
    )
    return {int(line) for line in result.stdout.splitlines() if line.strip().isdigit()}


class HelperSession:
    def __init__(
        self,
        baseline_gpu_pids: set[int],
        preferred_volume: int | None = None,
        environment_overrides: dict[str, str] | None = None,
    ) -> None:
        environment = dict(os.environ)
        environment.pop("IMF_AUDIO_DIAGNOSTICS", None)
        if environment_overrides:
            environment.update(environment_overrides)
        self.process = subprocess.Popen(
            [str(HELPER)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            env=environment,
        )
        assert self.process.stdout is not None
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)
        self.pending: list[dict[str, object]] = []
        self.gpu_pid: int | None = None
        # Match the production bridge's cold-WebKit allowance. Fifteen seconds is insufficient
        # after macOS has reclaimed WebKit services and makes the test create avoidable churn.
        self.ready = self.wait_type("ready", 30)
        load_command: dict[str, object] = {"cmd": "load", "url": "about:blank"}
        if preferred_volume is not None:
            load_command["preferredVolume"] = preferred_volume
        self.send(load_command)
        self.wait_type("loaded", 15)
        observed = self.wait_type("gpu_process_observed", 15)
        self.gpu_pid = int(observed["pid"])
        if self.gpu_pid in baseline_gpu_pids:
            raise AssertionError(f"helper reused pre-existing GPU pid {self.gpu_pid}")

    def send(self, message: dict[str, object]) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        self.process.stdin.flush()

    def read_fresh_message(self, timeout: float) -> dict[str, object]:
        events = self.selector.select(timeout)
        if not events:
            raise TimeoutError(f"helper message timeout; pid={self.process.pid}")
        assert self.process.stdout is not None
        line = self.process.stdout.readline()
        if not line:
            stderr = ""
            if self.process.stderr is not None:
                stderr = self.process.stderr.read()
            raise RuntimeError(
                f"helper exited rc={self.process.poll()} pid={self.process.pid}: {stderr}"
            )
        return json.loads(line)

    def wait_message(self, predicate, timeout: float) -> dict[str, object]:
        deadline = time.monotonic() + timeout
        deferred: list[dict[str, object]] = []
        try:
            while time.monotonic() < deadline:
                if self.pending:
                    message = self.pending.pop(0)
                else:
                    message = self.read_fresh_message(
                        max(0.01, deadline - time.monotonic())
                    )
                if predicate(message):
                    return message
                deferred.append(message)
        finally:
            self.pending = deferred + self.pending
        raise TimeoutError("did not receive expected helper message")

    def wait_type(self, message_type: str, timeout: float) -> dict[str, object]:
        return self.wait_message(lambda message: message.get("type") == message_type, timeout)

    def eval(self, script: str, timeout: float = 30) -> dict[str, object]:
        request_id = str(uuid.uuid4())
        self.send({"cmd": "eval", "id": request_id, "js": script})
        message = self.wait_message(
            lambda candidate: candidate.get("type") == "eval_result"
            and candidate.get("id") == request_id,
            timeout,
        )
        result = message.get("result")
        if not isinstance(result, dict):
            raise AssertionError(f"unexpected eval result: {message}")
        if "error" in result:
            raise AssertionError(f"helper eval failed: {result['error']}")
        return result

    def quit(self) -> None:
        if self.process.poll() is not None:
            return
        self.send({"cmd": "quit"})
        self.process.wait(timeout=10)


def tiny_wav_data_url(sample_value: int = 128) -> str:
    samples = bytes([sample_value] * 16)
    rate = 8_000
    header = (
        b"RIFF"
        + struct.pack("<I", 36 + len(samples))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate, 1, 8)
        + b"data"
        + struct.pack("<I", len(samples))
    )
    return "data:audio/wav;base64," + base64.b64encode(header + samples).decode("ascii")


def sine_wav_data_url(frequency: int = 440) -> str:
    rate = 44_100
    frame_count = rate
    amplitude = 12_000
    samples = b"".join(
        struct.pack("<h", round(amplitude * math.sin(2 * math.pi * frequency * frame / rate)))
        for frame in range(frame_count)
    )
    header = (
        b"RIFF"
        + struct.pack("<I", 36 + len(samples))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
        + b"data"
        + struct.pack("<I", len(samples))
    )
    return "data:audio/wav;base64," + base64.b64encode(header + samples).decode("ascii")


def spatial_volume_probe(
    session: HelperSession, *, require_precise_scaling: bool = True
) -> dict[str, object]:
    script = f"""
      (function() {{
        var media = new Audio();
        var ownership = function() {{}};
        media.addEventListener('ended', ownership);
        media.loop = true;
        media.volume = 1;
        media.src = {json.dumps(sine_wav_data_url())};

        var context = new AudioContext();
        var source = context.createMediaElementSource(media);
        var analyser = context.createAnalyser();
        analyser.fftSize = 4096;
        var silentSink = context.createGain();
        silentSink.gain.value = 0;
        source.connect(analyser);
        analyser.connect(silentSink);
        silentSink.connect(context.destination);

        function rms() {{
          var samples = new Float32Array(analyser.fftSize);
          analyser.getFloatTimeDomainData(samples);
          var sum = 0;
          for (var i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
          return Math.sqrt(sum / samples.length);
        }}

        window.__nra_spatial_volume_probe = {{done: false}};
        context.resume().then(function() {{ return media.play(); }}).then(function() {{
          var attempts = 0;
          function sampleFullSignal() {{
            var full = rms();
            attempts++;
            if (full < 0.03 && attempts < 20) {{
              setTimeout(sampleFullSignal, 100);
              return;
            }}
            media.volume = 0.35;
            setTimeout(function() {{
              var reduced = rms();
              window.__nra_spatial_volume_probe = {{
                done: true,
                full: full,
                reduced: reduced,
                ratio: full > 0 ? reduced / full : 0,
                exposedVolume: media.volume
              }};
              if (window.__nra_dispose_media) {{
                window.__nra_dispose_media(media, 'spatial-volume-probe');
              }} else {{
                try {{ media.pause(); }} catch (e) {{}}
                try {{ media.removeAttribute('src'); media.load(); }} catch (e) {{}}
              }}
              try {{ context.close(); }} catch (e) {{}}
            }}, 500);
          }}
          setTimeout(sampleFullSignal, 300);
        }}).catch(function(error) {{
          window.__nra_spatial_volume_probe = {{done: true, error: String(error)}};
        }});
        return {{started: true}};
      }})()
    """
    session.eval(script)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        result = session.eval("window.__nra_spatial_volume_probe")
        if result.get("done"):
            if "error" in result:
                raise AssertionError(f"spatial volume probe failed: {result['error']}")
            full = float(result.get("full", 0))
            reduced = float(result.get("reduced", 0))
            ratio = float(result.get("ratio", 0))
            if full < 0.1 or reduced >= full:
                raise AssertionError(f"spatial volume did not attenuate: {result}")
            if require_precise_scaling and (reduced < 0.03 or not 0.30 <= ratio <= 0.40):
                raise AssertionError(f"spatial volume scaling is incorrect: {result}")
            return result
        time.sleep(0.1)
    raise TimeoutError("spatial volume probe did not complete")


def suspended_spatial_recovery_probe(session: HelperSession) -> dict[str, object]:
    script = f"""
      (function() {{
        var media = new Audio();
        var ownership = function() {{}};
        media.addEventListener('ended', ownership);
        media.loop = true;
        media.volume = 0.35;
        media.src = {json.dumps(sine_wav_data_url())};

        var context = new AudioContext();
        var source = context.createMediaElementSource(media);
        var analyser = context.createAnalyser();
        analyser.fftSize = 4096;
        var silentSink = context.createGain();
        silentSink.gain.value = 0;
        source.connect(analyser);
        analyser.connect(silentSink);
        silentSink.connect(context.destination);

        function rms() {{
          var samples = new Float32Array(analyser.fftSize);
          analyser.getFloatTimeDomainData(samples);
          var sum = 0;
          for (var i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
          return Math.sqrt(sum / samples.length);
        }}

        window.__nra_suspended_spatial_probe = {{done: false}};
        setTimeout(function() {{
          context.suspend().then(function() {{
            var playSettled = false;
            media.play().then(function() {{ playSettled = true; }}, function() {{
              playSettled = true;
            }});
            setTimeout(function() {{
              var before = {{
                state: context.state,
                rms: rms(),
                playSettled: playSettled,
                health: window.__nra_audio_context_health()
              }};
              window.__nra_resumeAllAudio().then(function() {{
                setTimeout(function() {{
                  var after = {{
                    state: context.state,
                    rms: rms(),
                    playSettled: playSettled,
                    health: window.__nra_audio_context_health()
                  }};
                  window.__nra_suspended_spatial_probe = {{
                    done: true,
                    before: before,
                    after: after
                  }};
                  window.__nra_dispose_media(media, 'suspended-spatial-probe');
                  try {{ context.close(); }} catch (e) {{}}
                }}, 700);
              }});
            }}, 300);
          }});
        }}, 150);
        return {{started: true}};
      }})()
    """
    session.eval(script)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        result = session.eval("window.__nra_suspended_spatial_probe")
        if result.get("done"):
            before = result.get("before")
            after = result.get("after")
            if not isinstance(before, dict) or not isinstance(after, dict):
                raise AssertionError(f"malformed suspended spatial result: {result}")
            if before.get("state") != "suspended":
                raise AssertionError(f"context was not suspended before recovery: {result}")
            if after.get("state") != "running":
                raise AssertionError(f"context did not resume: {result}")
            before_rms = float(before.get("rms", 0))
            after_rms = float(after.get("rms", 0))
            if after_rms < 0.03 or before_rms > after_rms * 0.1:
                raise AssertionError(f"spatial PCM did not recover after resume: {result}")
            if not after.get("playSettled"):
                raise AssertionError(f"media.play() did not settle after context recovery: {result}")
            return result
        time.sleep(0.1)
    raise TimeoutError("suspended spatial recovery probe did not complete")


def media_health(session: HelperSession) -> dict[str, object]:
    return session.eval("window.__nra_media_health()")


def preferred_volume_gate_probe(session: HelperSession) -> dict[str, object]:
    source = tiny_wav_data_url()
    packet = "42" + json.dumps(
        [
            "data",
            {
                "type": "x.ClientCreateMediaPayload",
                "payload": {
                    "media": {
                        "mediaId": "preferred-volume-probe",
                        "source": source,
                        "volume": 60,
                    }
                },
            },
        ],
        separators=(",", ":"),
    )
    armed = session.eval(
        f"""
        (function() {{
          window.__nra_handle_relay_data({json.dumps(packet)});
          var media = new Audio();
          media.addEventListener('ended', function() {{}});
          media.src = {json.dumps(source)};
          media.volume = 0;
          window.__nra_volume_gate_probe_media = media;
          media.play().catch(function() {{}});
          return {{
            gateActive: window.__nra_volume_gate_active === true,
            preferredVolume: window.__nra_preferred_volume,
            masterVolume: window.__nra_master_volume_percent,
            mutedBeforeCommit: media.muted,
            elementVolumeBeforeCommit: media.volume
          }};
        }})()
        """
    )
    if (
        armed.get("gateActive") is not True
        or int(armed.get("preferredVolume", -1)) != 0
        or int(armed.get("masterVolume", -1)) != 0
        or armed.get("mutedBeforeCommit") is not True
    ):
        raise AssertionError(f"persisted volume gate was not armed before play: {armed}")
    committed_zero = session.eval(
        """
        (function() {
          window.__nra_commit_master_volume(0);
          return {muted: window.__nra_volume_gate_probe_media.muted,
                  gateActive: window.__nra_volume_gate_active === true};
        })()
        """
    )
    if committed_zero.get("muted") is not True or committed_zero.get("gateActive") is not False:
        raise AssertionError(f"committed zero did not remain silent: {committed_zero}")
    released = session.eval(
        """
        (function() {
          window.__nra_commit_master_volume(35);
          var media = window.__nra_volume_gate_probe_media;
          var health = window.__nra_media_health();
          var result = {
            muted: media.muted,
            elementVolume: media.volume,
            masterVolume: window.__nra_master_volume_percent,
            volumeRestored: health.volumeRestored,
            playingSilentLive: health.playingSilentLive
          };
          window.__nra_dispose_media(media, 'preferred-volume-gate-probe');
          window.__nra_volume_gate_probe_media = null;
          return result;
        })()
        """
    )
    if (
        released.get("muted") is not False
        or not math.isclose(float(released.get("elementVolume", -1)), 0.21, abs_tol=1e-9)
        or int(released.get("masterVolume", -1)) != 35
        or int(released.get("volumeRestored", 0)) < 1
    ):
        raise AssertionError(f"positive volume did not release forced mute: {released}")
    return {"armed": armed, "committedZero": committed_zero, "released": released}


def speaker_mute_policy_probe(session: HelperSession) -> dict[str, object]:
    packet = json.dumps(
        [
            "data",
            {
                "type": "x.ClientCreateMediaPayload",
                "payload": {
                    "media": {
                        "mediaId": "policy-probe",
                        "source": "https://media.invalid/policy-probe.ogg",
                        "muteSpeakers": True,
                        "muteRegions": True,
                    }
                },
            },
        ],
        separators=(",", ":"),
    )
    result = session.eval(
        f"""
        (function() {{
          var before = window.__nra_media_health();
          var packet = JSON.parse({json.dumps(packet)});
          var ordinary = JSON.parse('{{"muteSpeakers":true,"keep":7}}');
          var after = window.__nra_media_health();
          return {{
            muteSpeakers: packet[1].payload.media.muteSpeakers,
            muteRegions: packet[1].payload.media.muteRegions,
            ordinaryMuteSpeakers: ordinary.muteSpeakers,
            ordinaryKeep: ordinary.keep,
            suppressedDelta: after.muteSpeakersSuppressed - before.muteSpeakersSuppressed,
            lastSuppressedAt: after.lastMuteSpeakersSuppressedAt
          }};
        }})()
        """
    )
    if result.get("muteSpeakers") is not False:
        raise AssertionError(f"muteSpeakers was not disabled: {result}")
    if result.get("muteRegions") is not True:
        raise AssertionError(f"muteRegions was unexpectedly changed: {result}")
    if result.get("ordinaryMuteSpeakers") is not True or int(result.get("ordinaryKeep", 0)) != 7:
        raise AssertionError(f"ordinary JSON was unexpectedly changed: {result}")
    if int(result.get("suppressedDelta", 0)) != 1 or int(result.get("lastSuppressedAt", 0)) <= 0:
        raise AssertionError(f"muteSpeakers suppression telemetry is incorrect: {result}")
    return result


def stress_batch_start_script(data_url: str, count: int, offset: int) -> str:
    return f"""
      (function() {{
        window.__nra_integration_batch = [];
        for (var i = 0; i < {count}; i++) {{
          var element = new Audio();
          element.addEventListener('ended', function() {{}});
          element.src = {json.dumps(data_url)};
          window.__nra_integration_batch.push(element);
          element.play().catch(function() {{}});
        }}
        return {{started: window.__nra_integration_batch.length}};
      }})()
    """


def stress_batch_dispose_script() -> str:
    return """
      (function() {
        (window.__nra_integration_batch || []).forEach(function(element) {
          window.__nra_dispose_media(element, 'real-webkit-stress');
        });
        window.__nra_integration_batch = [];
        return window.__nra_media_health();
      })()
    """


def gpu_metrics(pid: int) -> dict[str, object]:
    vmmap = subprocess.run(
        ["/usr/bin/vmmap", "-summary", str(pid)],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout
    sample = subprocess.run(
        ["/usr/bin/sample", str(pid), "1", "1", "-mayDie"],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout
    footprint = re.search(r"Physical footprint:\s+([^\n]+)", vmmap)
    cache = re.search(r"CoreMedia HTTP cache\s+([^\n]+)", vmmap)
    return {
        "physicalFootprint": footprint.group(1).strip() if footprint else "unknown",
        "coreMediaHttpCache": cache.group(1).strip() if cache else "0 (no mapped region)",
        "audioqueueThreads": len(re.findall(r"com\.apple\.coremedia\.audioqueue\.source", sample)),
        "audiomentorThreads": len(re.findall(r"com\.apple\.coremedia\.audiomentor", sample)),
    }


def terminate_and_wait(process: subprocess.Popen[str], sig: int, timeout: float = 15) -> None:
    os.kill(process.pid, sig)
    process.wait(timeout=timeout)


def legacy_minimum_smoke_probe() -> dict[str, object]:
    baseline = gpu_pids()
    session: HelperSession | None = None
    try:
        session = HelperSession(
            baseline,
            environment_overrides={"IMF_AUDIO_INJECTION_MODE": "legacy-minimum"},
        )
        if session.ready.get("audioInjectionMode") != "legacy-minimum":
            raise AssertionError(f"explicit helper mode is not legacy-minimum: {session.ready}")
        surface = session.eval(
            """
            (function() {
              var health = window.__nra_media_health ? window.__nra_media_health() : null;
              return {
                mode: window.__nra_audio_injection_mode,
                healthMode: health ? health.mode : null,
                hasDisposeMedia: typeof window.__nra_dispose_media === 'function',
                hasRelayHook: typeof window.__nra_handle_relay_data === 'function',
                hasStaleGuard: window.__nra_stale_media_play_guard === true,
                created: health ? health.created : -1,
                live: health ? health.live : -1
              };
            })()
            """
        )
        expected = {
            "mode": "legacy-minimum",
            "healthMode": "legacy-minimum",
            "hasDisposeMedia": False,
            "hasRelayHook": False,
            "hasStaleGuard": False,
            "created": 0,
            "live": 0,
        }
        if surface != expected:
            raise AssertionError(f"legacy-minimum injection surface is wrong: {surface}")

        # The historical GainNode patch is intentionally less invasive and did not always
        # produce an exact 0.35 RMS ratio in real WKWebView. For this A/B mode, verify that
        # playback works and attenuates; keep the stricter accuracy check for managed mode.
        volume = spatial_volume_probe(session, require_precise_scaling=False)
        assert session.gpu_pid is not None
        metrics = gpu_metrics(session.gpu_pid)
        gpu_pid = session.gpu_pid
        session.quit()
        deadline = time.monotonic() + 10
        while gpu_pid in gpu_pids() and time.monotonic() < deadline:
            time.sleep(0.1)
        if gpu_pid in gpu_pids():
            raise AssertionError(f"legacy-minimum GPU process survived helper teardown: {gpu_pid}")
        return {
            "ready": session.ready,
            "surface": surface,
            "spatialVolume": volume,
            "gpuPid": gpu_pid,
            "gpuMetrics": metrics,
        }
    finally:
        if session is not None and session.process.poll() is None:
            terminate_and_wait(session.process, signal.SIGTERM)


def legacy_observe_smoke_probe() -> dict[str, object]:
    baseline = gpu_pids()
    session: HelperSession | None = None
    try:
        session = HelperSession(
            baseline,
            environment_overrides={"IMF_AUDIO_INJECTION_MODE": "legacy-observe"},
        )
        if session.ready.get("audioInjectionMode") != "legacy-observe":
            raise AssertionError(f"helper mode is not legacy-observe: {session.ready}")
        surface_script = (
            """
            (function() {
              var secretSource = 'https://secret.invalid/ariel.ogg?token=DO-NOT-LOG';
              var packet = ['data', {
                type: 'x.ClientCreateMediaPayload',
                payload: {media: {
                  mediaId: 'ariel-observe-probe', source: secretSource,
                  loop: false, doPickup: true, volume: 0.7,
                  muteSpeakers: true, muteRegions: false
                }}
              }];
              var parsed = JSON.parse(JSON.stringify(packet));
              var media = new Audio();
              media.src = __OBSERVE_WAV_DATA_URL__;
              media.addEventListener('ended', function ownership() {});
              window.__nra_legacy_observe_probe_media = media;
              var playResult = media.play();
              return {
                mode: window.__nra_audio_injection_mode,
                healthMode: window.__nra_media_health().mode,
                hasDisposeMedia: typeof window.__nra_dispose_media === 'function',
                hasRelayHook: typeof window.__nra_handle_relay_data === 'function',
                hasStaleGuard: window.__nra_stale_media_play_guard === true,
                hasSnapshot: typeof window.__nra_observe_snapshot === 'function',
                muteSpeakersPreserved: parsed[1].payload.media.muteSpeakers === true,
                playReturnedPromise: !!playResult && typeof playResult.then === 'function'
              };
            })()
            """
            .replace("__OBSERVE_WAV_DATA_URL__", json.dumps(tiny_wav_data_url()))
        )
        surface = session.eval(surface_script)
        expected = {
            "mode": "legacy-observe",
            "healthMode": "legacy-observe",
            "hasDisposeMedia": False,
            "hasRelayHook": False,
            "hasStaleGuard": False,
            "hasSnapshot": True,
            "muteSpeakersPreserved": True,
            "playReturnedPromise": True,
        }
        if surface != expected:
            raise AssertionError(f"legacy-observe injection surface is wrong: {surface}")

        volume = spatial_volume_probe(session, require_precise_scaling=False)
        time.sleep(0.5)
        evidence = session.eval(
            """
            (function() {
              var media = window.__nra_legacy_observe_probe_media;
              if (media) media.pause();
              var snapshot = window.__nra_observe_snapshot();
              return {snapshot: snapshot, health: window.__nra_media_health()};
            })()
            """
        )
        rendered = json.dumps(evidence, sort_keys=True)
        if "secret.invalid" in rendered or "DO-NOT-LOG" in rendered or "ariel.ogg" in rendered:
            raise AssertionError(f"legacy-observe leaked a media URL: {evidence}")
        kinds = {
            event.get("kind")
            for event in evidence["snapshot"].get("events", [])
            if isinstance(event, dict)
        }
        required_kinds = {
            "OBSERVE_INSTALLED",
            "RELAY_CREATE",
            "ENDED_HANDLER_ADD",
            "PLAY_CALL",
            "PLAY_RESOLVED",
            "PAUSE_CALL",
            "GRAPH_CREATE",
            "GRAPH_CONNECT",
        }
        if not required_kinds.issubset(kinds):
            raise AssertionError(
                f"legacy-observe missed real helper events: missing={required_kinds - kinds}"
            )
        if evidence["snapshot"].get("capacity") != 512:
            raise AssertionError(f"legacy-observe buffer is not bounded: {evidence}")

        assert session.gpu_pid is not None
        metrics = gpu_metrics(session.gpu_pid)
        gpu_pid = session.gpu_pid
        session.quit()
        deadline = time.monotonic() + 10
        while gpu_pid in gpu_pids() and time.monotonic() < deadline:
            time.sleep(0.1)
        if gpu_pid in gpu_pids():
            raise AssertionError(f"legacy-observe GPU process survived helper teardown: {gpu_pid}")
        return {
            "ready": session.ready,
            "surface": surface,
            "spatialVolume": volume,
            "evidence": evidence,
            "gpuPid": gpu_pid,
            "gpuMetrics": metrics,
        }
    finally:
        if session is not None and session.process.poll() is None:
            terminate_and_wait(session.process, signal.SIGTERM)


def legacy_guarded_stale_play_probe() -> dict[str, object]:
    baseline = gpu_pids()
    session: HelperSession | None = None
    try:
        session = HelperSession(
            baseline,
            environment_overrides={"IMF_AUDIO_INJECTION_MODE": "legacy-guarded"},
        )
        if session.ready.get("audioInjectionMode") != "legacy-guarded":
            raise AssertionError(f"helper mode is not legacy-guarded: {session.ready}")

        data_url = sine_wav_data_url()
        changed_data_url = sine_wav_data_url(441)
        stress_data_url = tiny_wav_data_url()
        initial = session.eval(
            f"""
            (function() {{
              var source = {json.dumps(data_url)};
              var stale = new Audio();
              stale.src = source;
              stale.volume = 0;
              stale.addEventListener('ended', function staleOwnership() {{}});
              var context = new AudioContext();
              var graph = context.createMediaElementSource(stale);
              graph.connect(context.destination);
              stale.pause();
              window.__nra_guard_stale_outcome = 'pending';
              stale.play().then(function() {{
                window.__nra_guard_stale_outcome = 'resolved';
              }}, function(error) {{
                window.__nra_guard_stale_outcome = error && error.name || 'rejected';
              }});

              var current = new Audio();
              current.src = source;
              current.volume = 0;
              current.addEventListener('ended', function currentOwnership() {{}});
              window.__nra_guard_current_outcome = 'pending';
              current.play().then(function() {{
                window.__nra_guard_current_outcome = 'resolved';
              }}, function(error) {{
                window.__nra_guard_current_outcome = error && error.name || 'rejected';
              }});

              var changed = new Audio();
              changed.src = source;
              changed.volume = 0;
              changed.addEventListener('ended', function changedOwnership() {{}});
              changed.pause();
              changed.src = {json.dumps(changed_data_url)};
              window.__nra_guard_changed_outcome = 'pending';
              changed.play().then(function() {{
                window.__nra_guard_changed_outcome = 'resolved';
              }}, function(error) {{
                window.__nra_guard_changed_outcome = error && error.name || 'rejected';
              }});
              window.__nra_guard_stale = stale;
              window.__nra_guard_current = current;
              window.__nra_guard_changed = changed;
              window.__nra_guard_context = context;
              return {{
                mode: window.__nra_audio_injection_mode,
                health: window.__nra_media_health()
              }};
            }})()
            """
        )
        if initial.get("mode") != "legacy-guarded":
            raise AssertionError(f"guard surface is unavailable: {initial}")

        deadline = time.monotonic() + 5
        outcome: dict[str, object] = {}
        while time.monotonic() < deadline:
            outcome = session.eval(
                """
                (function() {
                  var stale = window.__nra_guard_stale;
                  var current = window.__nra_guard_current;
                  var changed = window.__nra_guard_changed;
                  return {
                    staleOutcome: window.__nra_guard_stale_outcome,
                    currentOutcome: window.__nra_guard_current_outcome,
                    changedOutcome: window.__nra_guard_changed_outcome,
                    staleSourcePresent: !!(stale && (stale.currentSrc
                      || stale.getAttribute('src'))),
                    currentSourcePresent: !!(current && (current.currentSrc
                      || current.getAttribute('src'))),
                    changedSourcePresent: !!(changed && (changed.currentSrc
                      || changed.getAttribute('src'))),
                    currentTime: current ? current.currentTime : -1,
                    health: window.__nra_media_health()
                  };
                })()
                """
            )
            if (
                outcome.get("staleOutcome") != "pending"
                and outcome.get("currentOutcome") != "pending"
                and outcome.get("changedOutcome") != "pending"
                and float(outcome.get("currentTime", 0)) > 0
            ):
                break
            time.sleep(0.05)
        if outcome.get("staleOutcome") != "AbortError":
            raise AssertionError(f"stale media was not blocked before native play: {outcome}")
        if outcome.get("currentOutcome") != "resolved":
            raise AssertionError(f"same-source replacement did not play: {outcome}")
        if outcome.get("changedOutcome") != "resolved":
            raise AssertionError(f"source-revision replacement did not play: {outcome}")
        if outcome.get("staleSourcePresent") is not False:
            raise AssertionError(f"stale media source was not released: {outcome}")
        if outcome.get("currentSourcePresent") is not True:
            raise AssertionError(f"replacement media was incorrectly disposed: {outcome}")
        if outcome.get("changedSourcePresent") is not True:
            raise AssertionError(f"changed-source media was incorrectly disposed: {outcome}")
        if float(outcome.get("currentTime", 0)) <= 0:
            raise AssertionError(f"replacement media never advanced: {outcome}")
        health = outcome.get("health")
        if not isinstance(health, dict):
            raise AssertionError(f"guard health is missing: {outcome}")
        expected = {
            "staleCandidates": 2,
            "staleBlocked": 1,
            "staleDisposed": 1,
            "nativePlayForwarded": 2,
            "allowedFirstPlays": 2,
            "guardLiveCandidates": 0,
            "guardPendingTimers": 0,
        }
        for key, value in expected.items():
            if int(health.get(key, -1)) != value:
                raise AssertionError(f"guard counter {key} is wrong: {outcome}")

        assert session.gpu_pid is not None
        before_metrics = gpu_metrics(session.gpu_pid)
        before_stress = session.eval("window.__nra_media_health()")
        batch_size = 100
        total = 5_000
        for offset in range(0, total, batch_size):
            batch = session.eval(
                f"""
                (function() {{
                  var source = {json.dumps(stress_data_url)};
                  for (var index = 0; index < {batch_size}; index++) {{
                    var media = new Audio();
                    media.src = source;
                    media.addEventListener('ended', function ownership() {{}});
                    media.pause();
                    media.play().catch(function() {{}});
                  }}
                  return window.__nra_media_health();
                }})()
                """
            )
            if int(batch.get("guardLiveCandidates", -1)) != 0 or int(
                batch.get("guardPendingTimers", -1)
            ) != 0:
                raise AssertionError(f"guard batch left pending state at offset {offset}: {batch}")

        after_stress = session.eval("window.__nra_media_health()")
        if int(after_stress.get("staleBlocked", 0)) - int(
            before_stress.get("staleBlocked", 0)
        ) != total:
            raise AssertionError(f"guard did not block all stress media: {after_stress}")
        if int(after_stress.get("staleDisposed", 0)) - int(
            before_stress.get("staleDisposed", 0)
        ) != total:
            raise AssertionError(f"guard did not dispose all stress media: {after_stress}")
        if int(after_stress.get("nativePlayForwarded", 0)) != int(
            before_stress.get("nativePlayForwarded", 0)
        ):
            raise AssertionError(f"stress media reached native play: {after_stress}")
        if int(after_stress.get("guardLiveCandidates", -1)) != 0 or int(
            after_stress.get("guardPendingTimers", -1)
        ) != 0:
            raise AssertionError(f"guard stress did not return to baseline: {after_stress}")

        after_metrics = gpu_metrics(session.gpu_pid)
        session.eval(
            """
            (function() {
              try { window.__nra_guard_current.pause(); } catch (e) {}
              try { window.__nra_guard_current.removeAttribute('src'); } catch (e) {}
              try { window.__nra_guard_current.load(); } catch (e) {}
              try { window.__nra_guard_changed.pause(); } catch (e) {}
              try { window.__nra_guard_changed.removeAttribute('src'); } catch (e) {}
              try { window.__nra_guard_changed.load(); } catch (e) {}
              try { window.__nra_guard_context.close(); } catch (e) {}
              window.__nra_guard_stale = null;
              window.__nra_guard_current = null;
              window.__nra_guard_changed = null;
              window.__nra_guard_context = null;
              return {cleaned: true};
            })()
            """
        )
        gpu_pid = session.gpu_pid
        session.quit()
        deadline = time.monotonic() + 10
        while gpu_pid in gpu_pids() and time.monotonic() < deadline:
            time.sleep(0.1)
        if gpu_pid in gpu_pids():
            raise AssertionError(f"legacy-guarded GPU process survived helper teardown: {gpu_pid}")
        return {
            "ready": session.ready,
            "initial": initial,
            "outcome": outcome,
            "stressBefore": before_stress,
            "stressAfter": after_stress,
            "gpuMetricsBefore": before_metrics,
            "gpuMetricsAfter": after_metrics,
            "gpuPid": gpu_pid,
        }
    finally:
        if session is not None and session.process.poll() is None:
            terminate_and_wait(session.process, signal.SIGTERM)


def gpu_memory_watchdog_probe() -> dict[str, object]:
    policy_test = subprocess.run(
        [str(HELPER), "--self-test-gpu-memory-policy"],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    )
    policy_result = json.loads(policy_test.stdout.strip())
    if policy_result.get("success") is not True:
        raise AssertionError(f"GPU policy self-test failed: {policy_result}")

    original_gpu_pids = gpu_pids()
    pressure_session: HelperSession | None = None
    recovered_session: HelperSession | None = None
    try:
        pressure_session = HelperSession(
            original_gpu_pids,
            environment_overrides={
                "IMF_AUDIO_INJECTION_MODE": "managed-lifecycle",
                # Test the real proc_pid_rusage path and complete helper shutdown without
                # allocating gigabytes. Production defaults remain 1.5 GiB / 512 MiB.
                "IMF_GPU_ABSOLUTE_LIMIT_BYTES": "1",
                "IMF_GPU_RAPID_GROWTH_LIMIT_BYTES": str(8 * 1024 * 1024 * 1024),
                "IMF_GPU_TELEMETRY_INTERVAL_MS": "1000",
            },
        )
        assert pressure_session.gpu_pid is not None
        source = tiny_wav_data_url()
        pressure_session.eval(
            f"""
            (function() {{
              var media = new Audio();
              media.addEventListener('ended', function() {{}});
              media.loop = true;
              media.src = {json.dumps(source)};
              window.__nra_watchdog_probe_media = media;
              media.play().catch(function() {{}});
              return {{started: true}};
            }})()
            """
        )
        health = pressure_session.wait_type("gpu_memory_health", 10)
        if int(health.get("physicalFootprintBytes", 0)) <= 0:
            raise AssertionError(f"GPU footprint telemetry was empty: {health}")
        if int(health.get("pid", -1)) != pressure_session.gpu_pid:
            raise AssertionError(f"GPU footprint telemetry used the wrong pid: {health}")

        pressure = pressure_session.wait_type("gpu_memory_pressure", 10)
        if pressure.get("reason") != "absolute-footprint":
            raise AssertionError(f"unexpected GPU pressure reason: {pressure}")
        snapshot = pressure.get("mediaSnapshot")
        if not isinstance(snapshot, list) or not snapshot:
            raise AssertionError(f"GPU pressure did not capture live media: {pressure}")
        rendered_snapshot = json.dumps(snapshot, sort_keys=True)
        if "data:audio" in rendered_snapshot or "base64" in rendered_snapshot:
            raise AssertionError(f"GPU pressure snapshot leaked a raw media source: {snapshot}")
        if not all("sourceHash" in item for item in snapshot if isinstance(item, dict)):
            raise AssertionError(f"GPU pressure snapshot lacks source hashes: {snapshot}")

        pressure_session.process.wait(timeout=15)
        pressure_gpu_pid = pressure_session.gpu_pid
        deadline = time.monotonic() + 10
        while pressure_gpu_pid in gpu_pids() and time.monotonic() < deadline:
            time.sleep(0.1)
        if pressure_gpu_pid in gpu_pids():
            raise AssertionError(f"pressure GPU process survived helper teardown: {pressure_gpu_pid}")

        recovery_baseline = gpu_pids()
        recovered_session = HelperSession(recovery_baseline)
        recovered_health = recovered_session.wait_type("gpu_memory_health", 10)
        if int(recovered_health.get("physicalFootprintBytes", 0)) <= 0:
            raise AssertionError(f"recovered helper did not report GPU health: {recovered_health}")
        recovered_gpu_pid = recovered_session.gpu_pid
        recovered_session.quit()
        return {
            "policySelfTest": policy_result,
            "pressure": pressure,
            "oldGpuPid": pressure_gpu_pid,
            "recoveredGpuPid": recovered_gpu_pid,
            "recoveredHealth": recovered_health,
        }
    finally:
        for session in (pressure_session, recovered_session):
            if session is not None and session.process.poll() is None:
                terminate_and_wait(session.process, signal.SIGTERM)


def main() -> int:
    if sys.platform != "darwin":
        print("SKIP: real WebKit lifecycle test requires macOS")
        return 0
    if not HELPER.is_file() or not os.access(HELPER, os.X_OK):
        raise FileNotFoundError(f"build helper first: {HELPER}")

    if "--watchdog-only" in sys.argv[1:]:
        print(json.dumps(gpu_memory_watchdog_probe(), indent=2, sort_keys=True))
        return 0

    if "--legacy-minimum-only" in sys.argv[1:]:
        print(json.dumps(legacy_minimum_smoke_probe(), indent=2, sort_keys=True))
        return 0

    if "--legacy-observe-only" in sys.argv[1:]:
        print(json.dumps(legacy_observe_smoke_probe(), indent=2, sort_keys=True))
        return 0

    if "--legacy-guarded-only" in sys.argv[1:]:
        print(json.dumps(legacy_guarded_stale_play_probe(), indent=2, sort_keys=True))
        return 0

    original_gpu_pids = gpu_pids()
    first = HelperSession(
        original_gpu_pids,
        preferred_volume=0,
        environment_overrides={"IMF_AUDIO_INJECTION_MODE": "managed-lifecycle"},
    )
    assert first.gpu_pid is not None
    volume_gate_probe = preferred_volume_gate_probe(first)
    print("PREFERRED_VOLUME_GATE_PROBE=" + json.dumps(volume_gate_probe, sort_keys=True))
    policy_probe = speaker_mute_policy_probe(first)
    print("SPEAKER_MUTE_POLICY_PROBE=" + json.dumps(policy_probe, sort_keys=True))
    suspended_probe = suspended_spatial_recovery_probe(first)
    print("SUSPENDED_SPATIAL_PROBE=" + json.dumps(suspended_probe, sort_keys=True))
    volume_probe = spatial_volume_probe(first)
    print("SPATIAL_VOLUME_PROBE=" + json.dumps(volume_probe, sort_keys=True))
    data_url = tiny_wav_data_url()
    probe = first.eval(stress_batch_start_script(data_url, 1, -1))
    if int(probe["started"]) != 1:
        raise AssertionError(f"real playback probe failed to start: {probe}")
    time.sleep(1)
    probe_health = media_health(first)
    if int(probe_health["lastSuccessfulPlayAt"]) <= 0:
        raise AssertionError(f"real WKWebView media.play() probe failed: {probe_health}")
    first.eval(stress_batch_dispose_script())
    before = media_health(first)
    before_metrics = gpu_metrics(first.gpu_pid)
    batch_size = 100
    total = 5_000
    last_health = before
    for offset in range(0, total, batch_size):
        started = first.eval(stress_batch_start_script(data_url, batch_size, offset))
        if int(started["started"]) != batch_size:
            raise AssertionError(f"real stress batch failed to start: {started}")
        time.sleep(0.05)
        last_health = first.eval(stress_batch_dispose_script())
    pending_deadline = time.monotonic() + 10
    after = media_health(first)
    while int(after.get("playPending", 0)) > 0 and time.monotonic() < pending_deadline:
        time.sleep(0.1)
        after = media_health(first)
    after_metrics = gpu_metrics(first.gpu_pid)

    if int(after["created"]) - int(before["created"]) != total:
        raise AssertionError(f"real stress create mismatch: before={before} after={after}")
    if int(after["disposed"]) - int(before["disposed"]) != total:
        raise AssertionError(f"real stress dispose mismatch: before={before} after={after}")
    if int(after["live"]) != int(before["live"]):
        raise AssertionError(f"real stress live media did not return to baseline: {after}")
    if int(after.get("playPending", 0)) != 0:
        raise AssertionError(f"real stress left pending play promises: {after}")
    if int(last_health["lastSuccessfulPlayAt"]) < int(probe_health["lastSuccessfulPlayAt"]):
        raise AssertionError("real WKWebView successful-play timestamp regressed")

    killed_gpu_pid = first.gpu_pid
    os.kill(killed_gpu_pid, signal.SIGKILL)
    changed = first.wait_type("gpu_process_changed", 15)
    first.process.wait(timeout=15)

    second_baseline = gpu_pids()
    recovered_after_gpu = HelperSession(second_baseline)
    recovered_gpu_pid = recovered_after_gpu.gpu_pid
    terminate_and_wait(recovered_after_gpu.process, signal.SIGTERM)

    third_baseline = gpu_pids()
    recovered_after_helper = HelperSession(third_baseline)
    final_gpu_pid = recovered_after_helper.gpu_pid
    recovered_after_helper.quit()

    summary = {
        "stress": {
            "created": total,
            "disposed": total,
            "liveBaseline": before["live"],
            "liveAfter": after["live"],
            "healthAfter": after,
        },
        "speakerMutePolicy": policy_probe,
        "preferredVolumeGate": volume_gate_probe,
        "gpuMetricsBefore": before_metrics,
        "gpuMetricsAfter": after_metrics,
        "gpuRecovery": {
            "killedPid": killed_gpu_pid,
            "signal": changed,
            "recoveredPid": recovered_gpu_pid,
        },
        "helperRecovery": {
            "terminatedHelperPid": recovered_after_gpu.process.pid,
            "newHelperPid": recovered_after_helper.process.pid,
            "newGpuPid": final_gpu_pid,
        },
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
