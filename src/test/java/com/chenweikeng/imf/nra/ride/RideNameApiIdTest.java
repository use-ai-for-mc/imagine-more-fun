package com.chenweikeng.imf.nra.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RideNameApiIdTest {
  private static final Set<String> KNOWN_API_IDS =
      Set.of(
          "alice",
          "astroorbiter",
          "autopia",
          "btm",
          "buzz",
          "casey",
          "gadgets",
          "canoe",
          "monorail",
          "dlrr",
          "dumbo",
          "tikiroom",
          "nemo",
          "lincoln",
          "hm",
          "indy",
          "jc",
          "kingarthur",
          "teacups",
          "mainstreetcar",
          "matterhorn",
          "tram",
          "toads",
          "peoplemover",
          "peterpan",
          "pdj",
          "pirates",
          "rogerrabbit",
          "swew",
          "space",
          "splash",
          "rotr",
          "storybook",
          "pooh",
          "tomsawyerraft",
          "goldenzephyr",
          "goofy",
          "grr",
          "guardians",
          "incredi",
          "eww",
          "jcc",
          "jj",
          "llr",
          "mjj",
          "monstersinc",
          "palaround",
          "racers",
          "redcartrolley",
          "symphonyswings",
          "ariel",
          "ff",
          "heimlich",
          "tot",
          "hmh",
          "gotgmad",
          "hyperspace");

  @Test
  void mapsEveryKnownRideIdExactlyOnce() {
    Set<String> mappedIds =
        RideName.sortedByDisplayName().stream()
            .map(RideName::getApiId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());

    assertEquals(KNOWN_API_IDS, mappedIds);
    assertEquals(57, mappedIds.size());
    for (String apiId : KNOWN_API_IDS) {
      RideName ride = RideName.fromApiId(apiId);
      assertEquals(apiId, ride.getApiId());
    }
  }

  @Test
  void keepsUnknownIdsUnmappedAndMapsRecurringSeasonalIds() {
    assertEquals(RideName.UNKNOWN, RideName.fromApiId(null));
    assertEquals(RideName.UNKNOWN, RideName.fromApiId("polar-express"));
    assertEquals(RideName.RED_CAR_TROLLEY, RideName.fromApiId(" RedCarTrolley "));
    assertEquals(RideName.HAUNTED_MANSION_HOLIDAY, RideName.fromApiId("hmh"));
    assertEquals(
        RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK, RideName.fromApiId("gotgmad"));
    assertEquals(RideName.HYPERSPACE_MOUNTAIN, RideName.fromApiId("hyperspace"));
  }
}
