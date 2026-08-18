package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class AudioVolumeStoreTest {
  @TempDir Path tempDir;

  @Test
  void zeroSurvivesStoreRecreation() throws Exception {
    Path path = tempDir.resolve("nra-audio.json");
    AudioVolumeStore first = store(path);

    assertEquals(AudioVolumeState.UNKNOWN, first.load());
    assertTrue(first.save(0));
    assertEquals(0, store(path).load());
    assertTrue(Files.readString(path).contains("\"preferredVolume\": 0"));
  }

  @Test
  void rejectsOutOfRangeValues() {
    AudioVolumeStore store = store(tempDir.resolve("nra-audio.json"));

    assertFalse(store.save(-1));
    assertFalse(store.save(101));
    assertEquals(AudioVolumeState.UNKNOWN, store.load());
  }

  @Test
  void missingVolumeDoesNotBecomeZero() throws Exception {
    Path path = tempDir.resolve("nra-audio.json");
    Files.writeString(path, "{\"version\":1}");

    assertEquals(AudioVolumeState.UNKNOWN, store(path).load());
  }

  private static AudioVolumeStore store(Path path) {
    return new AudioVolumeStore(path, LoggerFactory.getLogger("AudioVolumeStoreTest"));
  }
}
