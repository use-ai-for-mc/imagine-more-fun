package com.chenweikeng.imf.nra.config.profile;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ProfileCommandHandler {

  private ProfileCommandHandler() {}

  /**
   * Executes the profile command to switch to a specific profile.
   *
   * @param profileName The profile name to search for (can contain spaces)
   * @return 1 if successful, 0 if profile not found
   */
  public static int executeProfileSwitch(String profileName) {
    Minecraft client = Minecraft.getInstance();

    // Try exact match first
    StoredProfile matchedProfile = findProfileExactMatch(profileName);

    // If no exact match, try lowercase/prefix matching
    if (matchedProfile == null) {
      matchedProfile = findProfilePrefixMatch(profileName);
    }

    if (matchedProfile != null) {
      ProfileManager.activateProfile(matchedProfile.id);
      Component message =
          Component.literal("§6✨ §e[IMF] §fSwitched to profile: §e" + matchedProfile.name);
      if (client.player != null) {
        client.player.sendSystemMessage(message);
      }
      return 1;
    } else {
      Component message = Component.literal("§6✨ §e[IMF] §c⚠ Profile not found: §f" + profileName);
      if (client.player != null) {
        client.player.sendSystemMessage(message);
      }
      return 0;
    }
  }

  /** Finds a profile with an exact name match. */
  private static StoredProfile findProfileExactMatch(String profileName) {
    if (profileName == null) {
      return null;
    }
    for (StoredProfile profile : ProfileManager.getAllProfiles()) {
      if (profile.name.equals(profileName)) {
        return profile;
      }
    }
    return null;
  }

  /**
   * Finds a profile using lowercase/prefix matching. Converts both the input and profile names to
   * lowercase and checks if any profile name starts with the input.
   */
  private static StoredProfile findProfilePrefixMatch(String profileName) {
    if (profileName == null) {
      return null;
    }
    String lowerInput = profileName.toLowerCase();
    for (StoredProfile profile : ProfileManager.getAllProfiles()) {
      if (profile.name.toLowerCase().startsWith(lowerInput)) {
        return profile;
      }
    }
    return null;
  }
}
