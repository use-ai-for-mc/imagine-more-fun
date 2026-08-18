package com.chenweikeng.imf.nra.audio;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persists the user's OpenAudioMC volume independently of profile/config switching. */
final class AudioVolumeStore {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Logger LOGGER = LoggerFactory.getLogger("AudioVolumeStore");

  private final Path path;
  private final Logger logger;
  private int persistedVolume = AudioVolumeState.UNKNOWN;

  AudioVolumeStore() {
    this(ImfStorage.nraAudioState(), LOGGER);
  }

  AudioVolumeStore(Path path, Logger logger) {
    this.path = path;
    this.logger = logger;
  }

  synchronized int load() {
    State state = ImfFileIO.readJson(path, GSON, State.class, logger, "OpenAudioMC audio state");
    if (state == null) {
      persistedVolume = AudioVolumeState.UNKNOWN;
      return persistedVolume;
    }
    if (state.preferredVolume == null || !AudioVolumeState.isValid(state.preferredVolume)) {
      logger.warn(
          "Ignoring invalid persisted OpenAudioMC volume {} from {}", state.preferredVolume, path);
      persistedVolume = AudioVolumeState.UNKNOWN;
      return persistedVolume;
    }
    persistedVolume = state.preferredVolume;
    return persistedVolume;
  }

  synchronized boolean save(int volume) {
    if (!AudioVolumeState.isValid(volume)) {
      return false;
    }
    if (volume == persistedVolume) {
      return true;
    }
    boolean saved =
        ImfFileIO.writeJsonAtomic(path, GSON, new State(volume), logger, "OpenAudioMC audio state");
    if (saved) {
      persistedVolume = volume;
    }
    return saved;
  }

  private static final class State {
    @SuppressWarnings("unused")
    private int version = 1;

    private Integer preferredVolume;

    @SuppressWarnings("unused")
    private State() {}

    private State(int preferredVolume) {
      this.preferredVolume = preferredVolume;
    }
  }
}
