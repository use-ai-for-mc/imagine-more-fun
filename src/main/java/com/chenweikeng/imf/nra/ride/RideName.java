package com.chenweikeng.imf.nra.ride;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum RideName {
  // Disneyland rides
  ALICE_IN_WONDERLAND("Alice in Wonderland", "alice", 206, "Fantasyland"),
  ASTRO_ORBITOR("Astro Orbitor", "astro", 88, "Tomorrowland"),
  AUTOPIA("Autopia", "autopia", 246, "Tomorrowland"),
  BIG_THUNDER_MOUNTAIN_RAILROAD("Big Thunder Mountain Railroad", "btm", 201, "Frontierland"),
  BUZZ_LIGHTYEAR_ASTRO_BLASTERS("Buzz Lightyear Astro Blasters", "buzz", 258, "Tomorrowland"),
  CASEY_JR_CIRCUS_TRAIN("Casey Jr. Circus Train", "casey", 152, "Fantasyland"),
  CHIP_N_DALES_GADGET_COASTER(
      "Chip 'N' Dale's Gadget Coaster", "Chip 'n' Dale's GADGETcoaster", "gadget", 44, "Toontown"),
  DAVY_CROCKETTS_EXPLORER_CANOES(
      "Davy Crockett's Explorer Canoes", "canoe", 150, "Critter Country"),
  DISNEYLAND_MONORAIL("Disneyland Monorail", "monorail", 485, "Tomorrowland"),
  DISNEYLAND_RAILROAD("Disneyland Railroad", "dlrr", 1008, "Mainstreet USA"),
  DUMBO_THE_FLYING_ELEPHANT("Dumbo the Flying Elephant", "dumbo", 105, "Fantasyland"),
  ENCHANTED_TIKI_ROOM(
      "Enchanted Tiki Room", "Walt Disney's Enchanted Tiki Room", "tiki", 750, "Adventureland"),
  FINDING_NEMO_SUBMARINE_VOYAGE("Finding Nemo Submarine Voyage", "nemo", 790, "Tomorrowland"),
  GREAT_MOMENTS_WITH_MR_LINCOLN("Great Moments with Mr. Lincoln", "lincoln", 372, "Mainstreet USA"),
  HAUNTED_MANSION("Haunted Mansion", "hm", 445, "New Orleans Square"),
  INDIANA_JONES_ADVENTURE(
      "Indiana Jones Adventure", "Indiana Jones Adventure", "indy", 225, "Adventureland"),
  JUNGLE_CRUISE("Jungle Cruise", "jungle", 431, "Adventureland"),
  KING_ARTHUR_CARROUSEL("King Arthur Carrousel", "kac", 130, "Fantasyland"),
  MAD_TEA_PARTY("Mad Tea Party", "madtea", 90, "Fantasyland"),
  MAIN_STREET_CARRIAGES(
      "Main Street Carriages", "Main Street Vehicles", "mainst", 381, "Mainstreet USA"),
  MATTERHORN_BOBSLEDS("Matterhorn Bobsleds", "matterhorn", 120, "Fantasyland"),
  MICKEY_AND_FRIENDS_PARKING_TRAM("Mickey & Friends Parking Tram", "parking", 550, "Esplanade"),
  MR_TOADS_WILD_RIDE("Mr Toad's Wild Ride", "mrtoads", 109, "Fantasyland"),
  PEOPLEMOVER("Peoplemover", "PeopleMover", "pm", 717, "Tomorrowland"),
  PETER_PANS_FLIGHT("Peter Pan's Flight", "peterpan", 139, "Fantasyland"),
  PINOCCHIOS_DARING_JOURNEY("Pinocchio's Daring Journey", "pinocchio", 168, "Fantasyland"),
  PIRATES_OF_THE_CARIBBEAN("Pirates of the Caribbean", "potc", 841, "New Orleans Square"),
  ROGER_RABBITS_CAR_TOON_SPIN(
      "Roger Rabbit's Car-Toon Spin", "Roger Rabbit's Car Toon Spin", "roger", 168, "Toontown"),
  SNOW_WHITES_ENCHANTED_WISH("Snow White's Enchanted Wish", "snowwhite", 122, "Fantasyland"),
  SPACE_MOUNTAIN("Space Mountain", "space", 180, "Tomorrowland"),
  SPLASH_MOUNTAIN("Splash Mountain", "splash", 484, "Critter Country"),
  STAR_WARS_RISE_OF_THE_RESISTANCE(
      "Star Wars: Rise of the Resistance", "rotr", 294, "Galaxy's Edge"),
  STORYBOOK_LAND_CANAL_BOATS("Storybook Land Canal Boats", "storybook", 303, "Fantasyland"),
  THE_MANY_ADVENTURES_OF_WINNIE_THE_POOH(
      "The Many Adventures of Winnie the Pooh", "pooh", 200, "Critter Country"),
  TOM_SAWYER_ISLAND_RAFTS(
      "Tom Sawyer Island Rafts",
      "Rafts to Pirate's Lair on Tom Sawyer Island",
      "tsi",
      129,
      "New Orleans Square"),

  // DCA rides
  GOLDEN_ZEPHYR("Golden Zephyr", "gz", 108, "Paradise Gardens"),
  GOOFYS_SKY_SCHOOL("Goofy's Sky School", "gss", 91, "Paradise Gardens"),
  GRIZZLY_RIVER_RUN("Grizzly River Run", "grr", 361, "Grizzly Peak"),
  GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT(
      "Guardians of the Galaxy: Mission Breakout",
      "Guardians of the Galaxy - Mission: BREAKOUT!",
      "gotg",
      123,
      "Avenger's Campus"),
  INCREDICOASTER("Incredicoaster", "incredi", 135, "Pixar Pier"),
  INSIDE_OUT_EMOTIONAL_WHIRLWIND("Inside Out Emotional Whirlwind", "insideout", 98, "Pixar Pier"),
  JESSIES_CRITTER_CAROUSEL("Jessie's Critter Carousel", "jessie", 120, "Pixar Pier"),
  JUMPIN_JELLYFISH("Jumpin' Jellyfish", "jumpin", 45, "Paradise Gardens"),
  LUIGIS_ROLICKIN_ROADSTERS("Luigi's Rollickin' Roadsters", "luigi", 94, "Cars Land"),
  MATERS_JUNKYARD_JAMBOREE("Mater's Junkyard Jamboree", "mater", 90, "Cars Land"),
  MONSTERS_INC_MIKE_AND_SULLEY_TO_THE_RESCUE(
      "Monster's Inc. Mike & Sulley to the Rescue!",
      "Monsters, Inc. Mike & Sulley to the Rescue!",
      "monster",
      230,
      "Hollywood Land"),
  PIXAR_PAL_AROUND("Pixar Pal-A-Round", "palaround", 551, "Pixar Pier"),
  RADIATOR_SPRINGS_RACERS("Radiator Springs Racers", "rsr", 267, "Cars Land"),
  RED_CAR_TROLLEY("Red Car Trolley", "rct", 300, "Buena Vista Street"),
  SILLY_SYMPHONY_SWINGS("Silly Symphony Swings", "silly", 87, "Paradise Gardens"),
  THE_LITTLE_MERMAID_ARIELS_UNDERSEA_ADVENTURE(
      "The Little Mermaid: Ariel's Undersea Adventure",
      "The Little Mermaid - Ariel's Undersea Adventure",
      "ariel",
      369,
      "Paradise Gardens"),

  // Retro
  FLIKS_FLYERS("Flik's Flyers", "flik", 98, "A Bug's Land"),
  HEIMLICHS_CHEW_CHEW_TRAIN("Heimlich's Chew Chew Train", "heimlich", 102, "A Bug's Land"),
  THE_TWILIGHT_ZONE_TOWER_OF_TERROR(
      "The Twilight Zone Tower of Terror", "tot", 125, "Hollywood Land"),

  // Seasonal
  HAUNTED_MANSION_HOLIDAY("Haunted Mansion Holiday", "hmh", 445, "New Orleans Square"),
  GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK(
      "Guardians of the Galaxy: Monsters After Dark",
      "Guardians of the Galaxy - Monsters After Dark",
      "gotgmad",
      123,
      "Avenger's Campus"),
  HYPERSPACE_MOUNTAIN("Hyperspace Mountain", "hyperspace", 180, "Tomorrowland"),

  // Unknown ride (for rides not in the enum)
  UNKNOWN("Unknown", "unknown", 99999, null);

  private final String matchName;
  private final String displayName;
  private final String shortName;
  private final int rideTime; // Ride time in seconds
  private final String land; // Land name, or null if unknown/none
  private static final Map<String, RideName> BY_MATCH_NAME = new HashMap<>();

  static {
    for (RideName ride : values()) {
      BY_MATCH_NAME.put(ride.matchName, ride);
    }
  }

  RideName(String matchName, String shortName, int rideTime, String land) {
    this(matchName, null, shortName, rideTime, land);
  }

  RideName(String matchName, String displayName, String shortName, int rideTime, String land) {
    this.matchName = matchName;
    this.displayName = displayName;
    this.shortName = shortName;
    this.rideTime = rideTime;
    this.land = land;
  }

  public String getDisplayName() {
    return displayName != null ? displayName : matchName;
  }

  public String getShortName() {
    return shortName;
  }

  /** Gets the ride time in seconds. */
  public int getRideTime() {
    return rideTime;
  }

  /** Gets the land this ride belongs to (e.g. "Fantasyland"), or null if none. */
  public String getLand() {
    return land;
  }

  /**
   * Maps a string (match name) to a RideName enum. Returns UNKNOWN if the string doesn't match any
   * known ride.
   */
  public static RideName fromMatchString(String matchName) {
    return BY_MATCH_NAME.getOrDefault(matchName, UNKNOWN);
  }

  /** Returns all rides (excluding UNKNOWN) sorted alphabetically by display name. */
  public static List<RideName> sortedByDisplayName() {
    return Arrays.stream(values())
        .filter(r -> r != UNKNOWN)
        .sorted(Comparator.comparing(RideName::getDisplayName, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /** Maps a RideName enum back to its string representation. */
  public String toMatchString() {
    return matchName;
  }

  /** Checks if this ride is a seasonal ride. */
  public boolean isSeasonal() {
    return this == HAUNTED_MANSION_HOLIDAY
        || this == GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK
        || this == HYPERSPACE_MOUNTAIN;
  }

  /**
   * Matches a truncated ride name (e.g. "Mr Toad's Wild R" or "Mr Toad's Wild R...") to the closest
   * RideName. Handles "..." suffix and prefix like "⏐ ". Returns UNKNOWN if no match.
   */
  public static RideName fromTruncatedString(String s) {
    if (s == null) return UNKNOWN;
    String cleaned = s.trim();
    if (cleaned.isEmpty()) return UNKNOWN;
    // Remove common prefixes
    if (cleaned.startsWith("⏐ ")) cleaned = cleaned.substring(2).trim();
    // Remove leading pipe (handle both ASCII | and Unicode full-width |)
    if (cleaned.startsWith("| ")) cleaned = cleaned.substring(2).trim();
    // Remove "..." suffix (truncation)
    if (cleaned.endsWith("...")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
    // Exact match first
    cleaned = cleaned.trim();
    RideName exact = fromMatchString(cleaned);
    if (exact != UNKNOWN) return exact;
    // Find displayName that starts with cleaned (truncated match)
    RideName best = UNKNOWN;
    int bestLen = Integer.MAX_VALUE;
    for (RideName r : values()) {
      if (r == UNKNOWN) continue;
      if (r.matchName.startsWith(cleaned) && r.matchName.length() < bestLen) {
        best = r;
        bestLen = r.matchName.length();
      }
    }
    // Special case for Rise of the Resistance (sidebar shows "Rise of the Resistance" without "Star
    // Wars:")
    if (best == UNKNOWN && "Rise of the Resistance".startsWith(cleaned)) {
      return STAR_WARS_RISE_OF_THE_RESISTANCE;
    }
    // Special case for Tower of Terror (sidebar shows "Tower of Terror" without "The Twilight
    // Zone:")
    if (best == UNKNOWN && "Tower of Terror".startsWith(cleaned)) {
      return THE_TWILIGHT_ZONE_TOWER_OF_TERROR;
    }
    // Special case for Chip 'N' Dale's Gadget Coaster (sidebar shows "Chip N Dale's Gadget Coaster"
    // without apostrophe in N)
    if (best == UNKNOWN && "Chip N Dale's Gadget Coaster".startsWith(cleaned)) {
      return CHIP_N_DALES_GADGET_COASTER;
    }
    // Special case for Astro Orbitor (sidebar shows "Astro Orbiter" with "er" instead of "or")
    if (best == UNKNOWN && "Astro Orbiter".startsWith(cleaned)) {
      return ASTRO_ORBITOR;
    }
    return best;
  }
}
