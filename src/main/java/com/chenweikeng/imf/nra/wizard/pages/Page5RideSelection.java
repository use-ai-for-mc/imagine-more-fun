package com.chenweikeng.imf.nra.wizard.pages;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.wizard.WizardPage;
import com.chenweikeng.imf.nra.wizard.layout.RenderBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class Page5RideSelection extends WizardPage {

  private static final List<RideName> DISNEYLAND_RIDES =
      Arrays.asList(
          RideName.ALICE_IN_WONDERLAND,
          RideName.ASTRO_ORBITOR,
          RideName.AUTOPIA,
          RideName.BIG_THUNDER_MOUNTAIN_RAILROAD,
          RideName.BUZZ_LIGHTYEAR_ASTRO_BLASTERS,
          RideName.CASEY_JR_CIRCUS_TRAIN,
          RideName.CHIP_N_DALES_GADGET_COASTER,
          RideName.DAVY_CROCKETTS_EXPLORER_CANOES,
          RideName.DISNEYLAND_MONORAIL,
          RideName.DISNEYLAND_RAILROAD,
          RideName.DUMBO_THE_FLYING_ELEPHANT,
          RideName.ENCHANTED_TIKI_ROOM,
          RideName.FINDING_NEMO_SUBMARINE_VOYAGE,
          RideName.GREAT_MOMENTS_WITH_MR_LINCOLN,
          RideName.HAUNTED_MANSION,
          RideName.INDIANA_JONES_ADVENTURE,
          RideName.JUNGLE_CRUISE,
          RideName.KING_ARTHUR_CARROUSEL,
          RideName.MAD_TEA_PARTY,
          RideName.MAIN_STREET_CARRIAGES,
          RideName.MATTERHORN_BOBSLEDS,
          RideName.MICKEY_AND_FRIENDS_PARKING_TRAM,
          RideName.MR_TOADS_WILD_RIDE,
          RideName.PEOPLEMOVER,
          RideName.PETER_PANS_FLIGHT,
          RideName.PINOCCHIOS_DARING_JOURNEY,
          RideName.PIRATES_OF_THE_CARIBBEAN,
          RideName.ROGER_RABBITS_CAR_TOON_SPIN,
          RideName.SNOW_WHITES_ENCHANTED_WISH,
          RideName.SPACE_MOUNTAIN,
          RideName.SPLASH_MOUNTAIN,
          RideName.STAR_WARS_RISE_OF_THE_RESISTANCE,
          RideName.STORYBOOK_LAND_CANAL_BOATS,
          RideName.THE_MANY_ADVENTURES_OF_WINNIE_THE_POOH,
          RideName.TOM_SAWYER_ISLAND_RAFTS);

  private static final List<RideName> DCA_RIDES =
      Arrays.asList(
          RideName.GOLDEN_ZEPHYR,
          RideName.GOOFYS_SKY_SCHOOL,
          RideName.GRIZZLY_RIVER_RUN,
          RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT,
          RideName.INCREDICOASTER,
          RideName.INSIDE_OUT_EMOTIONAL_WHIRLWIND,
          RideName.JESSIES_CRITTER_CAROUSEL,
          RideName.JUMPIN_JELLYFISH,
          RideName.LUIGIS_ROLICKIN_ROADSTERS,
          RideName.MATERS_JUNKYARD_JAMBOREE,
          RideName.MONSTERS_INC_MIKE_AND_SULLEY_TO_THE_RESCUE,
          RideName.PIXAR_PAL_AROUND,
          RideName.RADIATOR_SPRINGS_RACERS,
          RideName.RED_CAR_TROLLEY,
          RideName.SILLY_SYMPHONY_SWINGS,
          RideName.THE_LITTLE_MERMAID_ARIELS_UNDERSEA_ADVENTURE);

  private static final List<RideName> RETRO_RIDES =
      Arrays.asList(
          RideName.FLIKS_FLYERS,
          RideName.HEIMLICHS_CHEW_CHEW_TRAIN,
          RideName.THE_TWILIGHT_ZONE_TOWER_OF_TERROR);

  private static final List<RideName> SEASONAL_RIDES =
      Arrays.asList(
          RideName.HAUNTED_MANSION_HOLIDAY,
          RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK,
          RideName.HYPERSPACE_MOUNTAIN);

  public Page5RideSelection() {
    super(4);
  }

  @Override
  public Component getTitle() {
    return literal("Ride Selection");
  }

  @Override
  public List<RenderBlock> getBlocks(Minecraft client) {
    List<RenderBlock> blocks = new ArrayList<>();

    blocks.add(text(intro()));
    blocks.add(spacer(10));

    List<RenderBlock> leftColumn = new ArrayList<>();
    leftColumn.add(text(sectionHeader("Disneyland Park")));
    leftColumn.add(spacer(5));
    leftColumn.add(
        row(
            column(buildRideColumn(DISNEYLAND_RIDES, 0)),
            column(buildRideColumn(DISNEYLAND_RIDES, 1))));

    List<RenderBlock> rightColumn = new ArrayList<>();
    rightColumn.add(text(sectionHeader("Disney California Adventure")));
    rightColumn.add(spacer(5));
    rightColumn.add(
        row(column(buildRideColumn(DCA_RIDES, 0)), column(buildRideColumn(DCA_RIDES, 1))));
    rightColumn.add(spacer(5));
    rightColumn.add(text(sectionHeader("Retro Rides")));
    rightColumn.add(spacer(5));
    rightColumn.add(
        row(column(buildRideColumn(RETRO_RIDES, 0)), column(buildRideColumn(RETRO_RIDES, 1))));
    rightColumn.add(spacer(5));
    rightColumn.add(text(sectionHeader("Seasonal Rides")));
    rightColumn.add(spacer(5));
    rightColumn.add(text(colored("", ChatFormatting.DARK_GRAY)));
    rightColumn.add(spacer(3));
    rightColumn.add(
        row(
            column(buildRideColumn(SEASONAL_RIDES, 0)),
            column(buildRideColumn(SEASONAL_RIDES, 1))));

    blocks.add(
        row(
            column(leftColumn.toArray(new RenderBlock[0])),
            column(rightColumn.toArray(new RenderBlock[0]))));

    if (!readyToGoNext()) {
      blocks.add(spacer(25));
      Component text =
          Component.literal("Some ride counts are not loaded yet (with ")
              .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED)
              .append(Component.literal("[⚠]").withStyle(ChatFormatting.YELLOW))
              .append(Component.literal(").\nPlease open "))
              .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED)
              .append(link("/ridestats", "command:ridestats", ChatFormatting.YELLOW))
              .append(
                  " and go through Page 1/2 of Disneyland, Page 1 of Disney California Adventure, and Page 1 of Retro.")
              .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);

      blocks.add(text(text));
    }

    return blocks;
  }

  private RenderBlock[] buildRideColumn(List<RideName> rides, int columnIndex) {
    List<RenderBlock> blocks = buildRideBlocks(rides, columnIndex);
    return blocks.toArray(new RenderBlock[0]);
  }

  private List<RenderBlock> buildRideBlocks(List<RideName> rides, int columnIndex) {
    List<RenderBlock> blocks = new ArrayList<>();
    int total = rides.size();
    int half = (total + 1) / 2;
    int start = columnIndex == 0 ? 0 : half;
    int end = columnIndex == 0 ? half : total;

    for (int i = start; i < end; i++) {
      blocks.add(text(rideCheckbox(rides.get(i))));
      blocks.add(spacer(2));
    }
    return blocks;
  }

  private Component intro() {
    return literal("Select which rides to track. Checked rides appear in the tracker HUD.\n");
  }

  private Component sectionHeader(String title) {
    return colored(title, ChatFormatting.GOLD);
  }

  private Component rideCheckbox(RideName ride) {
    Set<String> hiddenRides = ModConfig.currentSetting.hiddenRides;
    boolean isHidden = hiddenRides.contains(ride.toMatchString());
    boolean isVisible = !isHidden;

    RideCountManager rideCountManager = RideCountManager.getInstance();
    boolean hasRideCount = rideCountManager.hasRideCount(ride);

    ChatFormatting nameColor;
    if (!hasRideCount && !ride.isSeasonal()) {
      nameColor = ChatFormatting.YELLOW;
    } else {
      nameColor = isVisible ? ChatFormatting.GREEN : ChatFormatting.RED;
    }

    Component displayNameLink;
    if (!hasRideCount && !ride.isSeasonal()) {
      displayNameLink = Component.literal(ride.getDisplayName()).withStyle(nameColor);
    } else {
      displayNameLink =
          link(ride.getDisplayName(), "ride:" + ride.toMatchString(), nameColor, false);
    }

    MutableComponent result = Component.empty();
    if (!hasRideCount && !ride.isSeasonal()) {
      result.append(Component.literal("[⚠]").withStyle(ChatFormatting.YELLOW));
    } else if (isVisible) {
      result.append(link("[✓]", "ride:" + ride.toMatchString(), nameColor));
    } else {
      result.append(link("[ ]", "ride:" + ride.toMatchString(), ChatFormatting.DARK_GRAY));
    }

    return result.append(literal(" ")).append(displayNameLink);
  }

  @Override
  protected boolean readyToGoNext() {
    RideCountManager rideCountManager = RideCountManager.getInstance();
    return rideCountManager.hasBasicCounts();
  }
}
