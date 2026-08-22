package com.chenweikeng.imf.nra.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RideNameApiIdTest {
  private static final Set<String> CURRENT_SCORED_API_IDS =
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
          "tot");

  @Test
  void mapsEveryValidatedCurrentScoredRideIdExactlyOnce() {
    Set<String> mappedIds =
        RideName.sortedByDisplayName().stream()
            .map(RideName::getApiId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());

    assertEquals(CURRENT_SCORED_API_IDS, mappedIds);
    assertEquals(54, mappedIds.size());
    for (String apiId : CURRENT_SCORED_API_IDS) {
      RideName ride = RideName.fromApiId(apiId);
      assertEquals(apiId, ride.getApiId());
    }
  }

  @Test
  void keepsUnknownAndSeasonalIdsUnmapped() {
    assertEquals(RideName.UNKNOWN, RideName.fromApiId(null));
    assertEquals(RideName.UNKNOWN, RideName.fromApiId("polar-express"));
    assertEquals(RideName.RED_CAR_TROLLEY, RideName.fromApiId(" RedCarTrolley "));
    assertNull(RideName.HAUNTED_MANSION_HOLIDAY.getApiId());
    assertNull(RideName.HYPERSPACE_MOUNTAIN.getApiId());
  }
}
