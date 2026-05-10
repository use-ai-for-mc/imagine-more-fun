package com.chenweikeng.imf.nra.simulator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hardcoded fixture for the prototype. Real persistence + name→UUID lookup is a later step.
 *
 * <p>Texture payloads + equipment SNBT were captured via DebugBridge from a live ride on 2026-04-29
 * and stored under {@code debug-dumps/players/}. The SNBT strings are pasted verbatim from the dump
 * JSONs — including embedded {@code PublicBukkitValues}, which {@link ItemRecipe} strips at parse
 * time.
 */
public final class GhostRoster {

  // ─── phrack50 equipment ────────────────────────────────────────────────────────────────────────

  private static final String PHRACK50_HEAD_SNBT =
      "{components:{\"minecraft:custom_data\":{PublicBukkitValues:{\"imaginefun:big-brother-item-id\":\"f9237aee-31d3-4c02-a4e0-4533b0eae806\",\"imaginefun:big-brother-item-original-owner\":1232,\"imaginefun:big-brother-item-server-origin\":\"disneyland1\"}},\"minecraft:custom_name\":{extra:[{bold:1b,color:\"aqua\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"One-Of-A-Kind Holographic Legendary Sparkly Supreme Extra Special Edition Electric Blue Swag Drip Shades\",underlined:0b}],text:\"\"},\"minecraft:damage\":968,\"minecraft:enchantment_glint_override\":1b,\"minecraft:tooltip_display\":{hidden_components:[\"minecraft:attribute_modifiers\",\"minecraft:potion_contents\",\"minecraft:container\",\"minecraft:banner_patterns\",\"minecraft:tropical_fish/pattern\",\"minecraft:bees\",\"minecraft:map_id\",\"minecraft:block_state\",\"minecraft:container_loot\",\"minecraft:jukebox_playable\",\"minecraft:painting/variant\",\"minecraft:charged_projectiles\",\"minecraft:instrument\",\"minecraft:block_entity_data\",\"minecraft:pot_decorations\",\"minecraft:bundle_contents\",\"minecraft:written_book_content\",\"minecraft:fireworks\",\"minecraft:firework_explosion\",\"minecraft:unbreakable\"]},\"minecraft:unbreakable\":{}},count:1,id:\"minecraft:netherite_sword\"}";

  private static final String PHRACK50_OFFHAND_SNBT =
      "{components:{\"minecraft:custom_data\":{PublicBukkitValues:{\"imaginefun:big-brother-item-id\":\"2ae13fd2-2fd4-49e6-9457-66f9332b9a31\"}},\"minecraft:custom_name\":{extra:[{bold:1b,color:\"blue\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"VIP Pin\",underlined:0b}],text:\"\"},\"minecraft:damage\":316,\"minecraft:lore\":[{extra:[{bold:0b,color:\"gray\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"Presented to \",underlined:0b},{bold:1b,color:\"white\",italic:0b,text:\"phrack50\"}],text:\"\"},{extra:[{bold:0b,color:\"aqua\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"Thank you for being an amazing supporter of IF! -Cam\",underlined:0b}],text:\"\"},{extra:[{bold:0b,color:\"light_purple\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"You're amazing! Really appreciate your contributions to the server :) - Nara\",underlined:0b}],text:\"\"}],\"minecraft:tooltip_display\":{hidden_components:[\"minecraft:unbreakable\"]},\"minecraft:unbreakable\":{}},count:1,id:\"minecraft:diamond_hoe\"}";

  // ─── Anziety equipment ─────────────────────────────────────────────────────────────────────────

  private static final String ANZIETY_HEAD_SNBT =
      "{components:{\"minecraft:custom_data\":{PublicBukkitValues:{\"imaginefun:big-brother-item-id\":\"ad926415-3adf-48f2-8870-a84073e72aff\"}},\"minecraft:custom_name\":{extra:[{bold:1b,color:\"aqua\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"DVC\",underlined:0b},{bold:1b,color:\"gold\",italic:0b,text:\"+ \"},{bold:1b,color:\"aqua\",italic:0b,text:\"Angy\"}],text:\"\"},\"minecraft:damage\":1357,\"minecraft:lore\":[{extra:[{bold:0b,color:\"gray\",italic:1b,obfuscated:0b,strikethrough:0b,text:\"DVC+ Month 8 Exclusive\",underlined:0b}],text:\"\"},{extra:[{bold:1b,color:\"#97CFF5\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"Woof Woof -Naras Dog\",underlined:0b}],text:\"\"},{extra:[{bold:1b,color:\"#F2B5E1\",italic:0b,obfuscated:0b,strikethrough:0b,text:\"She said the IASW release date^ - Nara\",underlined:0b}],text:\"\"}],\"minecraft:unbreakable\":{}},count:1,id:\"minecraft:diamond_sword\"}";

  // ─── Roster ────────────────────────────────────────────────────────────────────────────────────

  private static final Map<String, GhostSpec> FIXTURE = new LinkedHashMap<>();

  static {
    add(
        new GhostSpec(
            UUID.fromString("574e88e5-76ae-40ab-af49-aa33b0eda755"),
            "phrack50",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzQwMTc1Njg0MCwKICAicHJvZmlsZUlkIiA6ICI1NzRlODhlNTc2YWU0MGFiYWY0OWFhMzNiMGVkYTc1NSIsCiAgInByb2ZpbGVOYW1lIiA6ICJwaHJhY2s1MCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iZjY1NmU3MjU1MzQwMzlhMjIyOTY4OTJlZjM2MDI0MGFlNzFlMTM2ZTM2OTdlOWY0NzY2NTZjZjk4N2I1OGMyIgogICAgfQogIH0KfQ==",
            "pROJLNwW9FjYLf2BlHYaIOMfaJobWowikZVRs7i1s1iL0FlZEI/1XlXf9kn9tWliHToJu+eeNDaJdebowIdWpTRGYIbuez1nu3RygWxT27CBuovDjtcRgbVnDT/5mYTW1errtTatPsaJAbNIJfhyMqeoqZfjaMcSJZY5Hbm2hGbj6P2/cOVHnpejFXR/KLmcEobunHqJkpdUbVabi6CCvPn6TzT0Mx+JsLXScZwjZYznw2CybbzTaSdzpgMrF1PmaMkaEGho/y3R/clt492R9EQWzBJ42EQdXCsdhBTv0nNh15knZTFVjOxYjvtN9n2AP64ebAcgik50K3agOh58phmU9e8RJ9eiYC8wrocA5BjiETQvV2KDVtvz4/KxBDuXZp/1yk3XYT/hMd64CxiCONcK05pmHbXcfcMNBWhifLbzf8SD45jqu1se8IarnJJ1B5halsbumqqjun/Ajmo7uKajY5US6cVRCaWurp9Bgf37CdpE/m2ZCDYbnfZhU4oqY6UvUBL/uD1WADPOmr7jfAzY80lFTehlXwvkP6iL+navND0Ine8RhMhjl82+XNt4xZhn8yqCRDiWkJ6veyyiYrv9Kb4SgCXXG/6j3eOwJENJGy3kFswymsXmauXhEVQk1sOUssv9zQi/NU2XFo7XpdaZ4CxS1PViOx6T1syFf84=",
            Map.of("head", PHRACK50_HEAD_SNBT, "offhand", PHRACK50_OFFHAND_SNBT)));
    add(
        new GhostSpec(
            UUID.fromString("b2e63fa5-fd35-4e29-b656-8c45c00720b6"),
            "Anziety",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzQwMTQxMTQwNiwKICAicHJvZmlsZUlkIiA6ICJiMmU2M2ZhNWZkMzU0ZTI5YjY1NjhjNDVjMDA3MjBiNiIsCiAgInByb2ZpbGVOYW1lIiA6ICJBbnppZXR5IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgxZjMxYThkMjNlOTUxNDYxZTFhNTU2N2U3Y2UxNzBmYTQ5ZTcwMWI2NzFmNDA2NjA1OWMzZmNjY2EzZDUyMWQiCiAgICB9LAogICAgIkNBUEUiIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzI4ZGU0YTgxNjg4YWQxOGI0OWU3MzVhMjczZTA4NmMxOGYxZTM5NjY5NTYxMjNjY2I1NzQwMzRjMDZmNWQzMzYiCiAgICB9CiAgfQp9",
            "GcgJnKTb6QiZXyytendaqWXE85LKbbSyPdnkjiUPFSGN8GCrbf7N/lvnzGzDSKGNYFJHFBkkvjFItwnCxExvWw6uBQqa12CDSjpdyb+sYD4gCmqxPnvwgUR5YjDzQjk+C1wkZrnuQaITii6pmpWJy9ZgWHh1K6Cp+VZ82nlVMNtphIlmdR3JGrrxDqkUX+sA2njrF8fx5/W0kd27keyZ2T7XSou5zQDeTAx9ng/35P2kOXuUaigX+U5FTDhXkyiaQOxkWfL7h9DjmmOJf3+gQSvIRBIULYkM2l/eoov+/VFytMoftjTr4wiCiydJIt+CDJCEI3Yb3MAGfO/cKH+5YY3V7l/SEClZaZ9l1bL4HVRmUalWLnRnor4oKrUyNwXnnt/bwBEWjDGcTsP6MOWIEuneqtVfc8eLjRQ2NQ+SREZwuKG+ytIQuuO1s0DMmZfV0c0faDe1evJ2zL92aH0pKmPnrswAL9C/0EqXOxvl1HfrWT8ULuopLb1LxlJZeNVlH94ZZygqLr0SXYhzjy1k//HaG7BdZAy8pUt1oJoqtIUKykpmjsDYklgmhsOgrJbJDnrN0nhtSW/KD0kPxh56riSxCWRfWDCRc+UMB6hxM7tb/v+8FuDthTTJdNH+/ALNz5v01FHExfBF3snyakg6VJj5SeeWKXw//+NbN2Sa/lQ=",
            Map.of("head", ANZIETY_HEAD_SNBT)));
  }

  private static void add(GhostSpec spec) {
    FIXTURE.put(spec.name(), spec);
  }

  private GhostRoster() {}

  public static GhostSpec byName(String name) {
    return FIXTURE.get(name);
  }

  public static Collection<GhostSpec> all() {
    return FIXTURE.values();
  }
}
