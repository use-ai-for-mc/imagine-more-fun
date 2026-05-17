package com.chenweikeng.imf.nra.config.profile;

import com.chenweikeng.imf.nra.config.ConfigSetting;
import com.chenweikeng.imf.nra.config.ModConfig;
import java.util.ArrayList;
import java.util.List;

public final class ProfileManager {
  private static List<StoredProfile> profiles = new ArrayList<>();

  private ProfileManager() {}

  public static void load() {
    ProfileStorage.ProfileStorageData data = ProfileStorage.load();
    profiles = data.profiles != null ? data.profiles : new ArrayList<>();
  }

  public static void save() {
    ProfileStorage.ProfileStorageData data = new ProfileStorage.ProfileStorageData(1, profiles);
    ProfileStorage.save(data);
  }

  public static List<StoredProfile> getAllProfiles() {
    return new ArrayList<>(profiles);
  }

  public static StoredProfile getProfile(String id) {
    if (id == null) return null;
    return profiles.stream().filter(p -> id.equals(p.id)).findFirst().orElse(null);
  }

  public static StoredProfile saveCurrentAsProfile(String name, String description) {
    StoredProfile profile = StoredProfile.fromCurrentConfig(name, description);
    profiles.add(profile);
    save();
    ModConfig.save();
    return profile;
  }

  public static StoredProfile addProfile(
      String id, String name, String description, ConfigSetting data) {
    StoredProfile profile = new StoredProfile(id, name, description, data);
    profiles.add(profile);
    save();
    return profile;
  }

  public static StoredProfile addProfileAtStart(
      String id, String name, String description, ConfigSetting data) {
    StoredProfile profile = new StoredProfile(id, name, description, data);
    profiles.add(0, profile);
    save();
    return profile;
  }

  public static boolean deleteProfile(String id) {
    StoredProfile profile = getProfile(id);
    if (profile == null) {
      return false;
    }
    profiles.remove(profile);
    save();
    return true;
  }

  public static void activateProfile(String id) {
    StoredProfile profile = getProfile(id);
    if (profile == null) return;
    HistoryManager.backupIfNeeded();
    ModConfig.currentSetting = profile.data.copy();
    ModConfig.save();
  }

  public static boolean isNameUnique(String name, String excludeId) {
    return profiles.stream()
        .noneMatch(p -> p.name.equalsIgnoreCase(name) && !p.id.equals(excludeId));
  }

  public static boolean renameProfile(String id, String newName, String newDescription) {
    StoredProfile profile = getProfile(id);
    if (profile == null) {
      return false;
    }
    profile.name = newName;
    profile.description = newDescription;
    profile.modifiedAt = System.currentTimeMillis();
    save();
    return true;
  }

  public static boolean isCurrentProfile(StoredProfile profile) {
    if (profile == null) return false;
    return profile.data.equals(ModConfig.currentSetting);
  }
}
