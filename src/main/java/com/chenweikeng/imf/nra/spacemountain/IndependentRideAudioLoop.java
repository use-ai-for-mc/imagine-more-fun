package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * A looping PCM sound played through Java Sound instead of Minecraft's sound engine.
 *
 * <p>Dynamic FPS changes Minecraft's sound-category volumes when the window loses focus. A Java
 * Sound {@link Clip} owns an independent output line, so its gain is unaffected by those mixins and
 * behaves like the separate OpenAudioMC helper. The clip stays loaded between rides to avoid a
 * start-of-ride decode/device-open delay.
 */
final class IndependentRideAudioLoop {
  private static final float VOLUME_SLEW = 0.04f;

  private final String resourcePath;
  private final String label;

  private Clip clip;
  private FloatControl gainControl;
  private float currentVolume;
  private float lastAppliedDb = Float.NaN;
  private boolean loadFailed;

  IndependentRideAudioLoop(String resourcePath, String label) {
    this.resourcePath = resourcePath;
    this.label = label;
  }

  boolean start() {
    if (!ensureLoaded()) {
      return false;
    }
    currentVolume = 0.0f;
    applyVolume(0.0f);
    clip.stop();
    clip.setFramePosition(0);
    clip.setLoopPoints(0, -1);
    clip.loop(Clip.LOOP_CONTINUOUSLY);
    return true;
  }

  void stop() {
    currentVolume = 0.0f;
    if (clip == null) {
      return;
    }
    applyVolume(0.0f);
    clip.stop();
    clip.setFramePosition(0);
  }

  void tick(float targetVolume) {
    if (clip == null) {
      return;
    }
    float target = Math.max(0.0f, Math.min(1.0f, targetVolume));
    float diff = target - currentVolume;
    if (Math.abs(diff) <= VOLUME_SLEW) {
      currentVolume = target;
    } else {
      currentVolume += Math.signum(diff) * VOLUME_SLEW;
    }
    applyVolume(currentVolume);
  }

  private boolean ensureLoaded() {
    if (clip != null) {
      return true;
    }
    if (loadFailed) {
      return false;
    }

    try (InputStream raw = IndependentRideAudioLoop.class.getResourceAsStream(resourcePath)) {
      if (raw == null) {
        throw new IOException("resource not found: " + resourcePath);
      }
      try (BufferedInputStream buffered = new BufferedInputStream(raw);
          AudioInputStream audio = AudioSystem.getAudioInputStream(buffered)) {
        clip = AudioSystem.getClip();
        clip.open(audio);
      }

      if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
        clip.close();
        clip = null;
        throw new LineUnavailableException("default Java Sound clip has no MASTER_GAIN control");
      }
      gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
      applyVolume(0.0f);
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainRideAudio] loaded independent {} loop from {}", label, resourcePath);
      return true;
    } catch (IOException
        | LineUnavailableException
        | UnsupportedAudioFileException
        | RuntimeException e) {
      if (clip != null) {
        clip.close();
        clip = null;
      }
      loadFailed = true;
      NotRidingAlertClient.LOGGER.error(
          "[SpaceMountainRideAudio] failed to load independent {} loop", label, e);
      return false;
    }
  }

  private void applyVolume(float linearVolume) {
    if (gainControl == null) {
      return;
    }
    float db =
        linearVolume <= 0.0001f
            ? gainControl.getMinimum()
            : (float) (20.0 * Math.log10(linearVolume));
    db = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), db));
    if (Float.isNaN(lastAppliedDb) || Math.abs(db - lastAppliedDb) >= 0.05f) {
      gainControl.setValue(db);
      lastAppliedDb = db;
    }
  }
}
