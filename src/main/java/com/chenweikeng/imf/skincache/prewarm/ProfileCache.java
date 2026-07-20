package com.chenweikeng.imf.skincache.prewarm;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.skincache.SkinCacheMod;
import com.chenweikeng.imf.skincache.persistence.CoalescedSaveQueue;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persisted cache mapping player UUID → skin texture metadata.
 *
 * <p>When Mojang's session server resolves a profile, we capture: - texture URL (e.g.
 * http://textures.minecraft.net/texture/abc123...) - texture hash (from
 * MinecraftProfileTexture.getHash()) - the Identifier path used by SkinManager's TextureCache
 * ("skins/<sha1>")
 *
 * <p>On subsequent chunk loads, if we have this mapping AND the PNG is in our TextureCache, we can
 * register the texture synchronously on the main thread, completely bypassing the async chain.
 *
 * <p>Eviction policy mirrors {@link com.chenweikeng.imf.skincache.cache.TextureCache}: - Hard TTL:
 * profiles unaccessed for {@link #DEFAULT_TTL_MS} are dropped. - Capacity LRU: when over {@link
 * #MAX_PROFILES}, lowest-{@code lastAccessed} go first.
 *
 * <p>Profiles are kept longer than textures (90 days vs 30) because they're tiny and being wrong is
 * harmless: a stale profile guess just falls through to the vanilla async lookup path.
 */
public final class ProfileCache {

  /** Compact JSON substantially reduces allocation and write volume for the 20k-entry index. */
  private static final Gson GSON = new Gson();

  /** Hard TTL: profiles unaccessed for longer than this are evicted. */
  private static final long DEFAULT_TTL_MS = 90L * 24 * 60 * 60 * 1000L; // 90 days

  /** Capacity cap. When exceeded, lowest-{@code lastAccessed} entries are evicted first. */
  private static final long MAX_PROFILES = 20_000;

  /**
   * Coarse-grained access tracking: only update {@code lastAccessed} if at least this much elapsed.
   */
  private static final long ACCESS_UPDATE_GRANULARITY_MS = 5 * 60 * 1000L; // 5 min

  /** Periodic maintenance interval. */
  private static final long PERIODIC_INTERVAL_SEC = 60;

  /** New/changed profiles are persisted at most once per debounce window. */
  private static final long SAVE_DEBOUNCE_SEC = 30;

  /** Access-only timestamps do not need crash-level durability; persist them in coarse batches. */
  private static final long ACCESS_PERSIST_INTERVAL_MS = 15 * 60 * 1000L;

  private static Path cacheFile;
  private static final ConcurrentHashMap<String, ProfileEntry> profiles = new ConcurrentHashMap<>();

  private static final ScheduledExecutorService SAVE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "skincache-profile-save");
            t.setDaemon(true);
            return t;
          });

  /** Set by access-time bumps; flushed only by the periodic maintenance thread. */
  private static final AtomicBoolean accessDirty = new AtomicBoolean(false);

  private static final CoalescedSaveQueue SAVE_QUEUE =
      new CoalescedSaveQueue(
          SAVE_EXECUTOR, SAVE_DEBOUNCE_SEC, TimeUnit.SECONDS, ProfileCache::saveIndex);

  private static volatile long lastSuccessfulSaveMs;

  private ProfileCache() {}

  public static void init() {
    Path gameDir = FabricLoader.getInstance().getGameDir();
    cacheFile = gameDir.resolve("skincache").resolve("profiles.json");
    lastSuccessfulSaveMs = System.currentTimeMillis();
    loadIndex();
    evictExpired();

    SAVE_EXECUTOR.scheduleAtFixedRate(
        ProfileCache::periodicMaintenance,
        PERIODIC_INTERVAL_SEC,
        PERIODIC_INTERVAL_SEC,
        TimeUnit.SECONDS);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    SAVE_QUEUE.flushNow();
                  } catch (Exception ignored) {
                  }
                },
                "skincache-profile-shutdown"));
  }

  /**
   * Store a resolved profile's texture info.
   *
   * @param uuid Player UUID (key)
   * @param textureUrl Full texture URL from Mojang
   * @param textureHash The hash from MinecraftProfileTexture.getHash()
   */
  public static void put(String uuid, String textureUrl, String textureHash) {
    long now = System.currentTimeMillis();
    String textureIdPath = "skins/" + Hashing.sha1().hashUnencodedChars(textureHash).toString();
    ProfileEntry current = profiles.get(uuid);

    // registerTextures can be called repeatedly for the same resolved profile. Treat an identical
    // mapping as an access instead of rewriting the entire 20k-entry JSON index.
    if (matches(current, uuid, textureUrl, textureHash, textureIdPath)) {
      bumpAccess(current, now, effectiveLastAccessed(current));
      return;
    }

    ProfileEntry entry = new ProfileEntry();
    entry.uuid = uuid;
    entry.textureUrl = textureUrl;
    entry.textureHash = textureHash;
    // Replicate SkinManager.TextureCache.registerTexture's ID generation:
    // sha1(textureHash) -> "skins/<sha1>"
    entry.textureIdPath = textureIdPath;
    entry.timestamp = current != null && current.timestamp > 0 ? current.timestamp : now;
    entry.lastAccessed = now;
    profiles.put(uuid, entry);
    saveAsync();
  }

  /** Look up cached profile entry by UUID. Updates {@code lastAccessed} (coarse-bucket) on hits. */
  public static ProfileEntry get(String uuid) {
    ProfileEntry entry = profiles.get(uuid);
    if (entry == null) return null;

    long now = System.currentTimeMillis();
    long lastAccess = effectiveLastAccessed(entry);
    if (now - lastAccess > DEFAULT_TTL_MS) {
      // Stale; evict opportunistically
      profiles.remove(uuid);
      saveAsync();
      return null;
    }

    bumpAccess(entry, now, lastAccess);
    return entry;
  }

  /**
   * Compute the texture Identifier path that SkinManager would use for a given texture hash. This
   * must match SkinManager.TextureCache.getTextureLocation().
   */
  public static String computeTextureIdPath(String textureHash) {
    return "skins/" + Hashing.sha1().hashUnencodedChars(textureHash).toString();
  }

  public static int size() {
    return profiles.size();
  }

  public static java.util.Collection<ProfileEntry> allEntries() {
    return profiles.values();
  }

  // ── Cache maintenance ──────────────────────────────────────────

  /** TTL eviction. */
  public static void evictExpired() {
    long now = System.currentTimeMillis();
    int removed = 0;

    Iterator<Map.Entry<String, ProfileEntry>> it = profiles.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, ProfileEntry> mapEntry = it.next();
      ProfileEntry e = mapEntry.getValue();
      if (now - effectiveLastAccessed(e) > DEFAULT_TTL_MS) {
        it.remove();
        removed++;
      }
    }

    if (removed > 0) {
      SkinCacheMod.LOGGER.debug("[SkinCache] Evicted {} expired profiles", removed);
      saveAsync();
    }
  }

  /** Capacity eviction. */
  public static void evictOverflow() {
    if (profiles.size() <= MAX_PROFILES) return;

    profiles.entrySet().stream()
        .sorted(
            Map.Entry.comparingByValue(
                (a, b) -> Long.compare(effectiveLastAccessed(a), effectiveLastAccessed(b))))
        .limit(profiles.size() - MAX_PROFILES)
        .forEach(entry -> profiles.remove(entry.getKey()));

    saveAsync();
  }

  private static void periodicMaintenance() {
    try {
      evictExpired();
      evictOverflow();
      long now = System.currentTimeMillis();
      if (accessDirty.get() && now - lastSuccessfulSaveMs >= ACCESS_PERSIST_INTERVAL_MS) {
        SAVE_QUEUE.requestSave();
      }
    } catch (Exception e) {
      SkinCacheMod.LOGGER.warn("[SkinCache] Profile cache periodic maintenance failed", e);
    }
  }

  // ── Access tracking helpers ────────────────────────────────────

  private static long effectiveLastAccessed(ProfileEntry e) {
    return e.lastAccessed > 0 ? e.lastAccessed : e.timestamp;
  }

  private static void bumpAccess(ProfileEntry entry, long now, long lastAccess) {
    if (now - lastAccess > ACCESS_UPDATE_GRANULARITY_MS) {
      entry.lastAccessed = now;
      accessDirty.set(true);
    }
  }

  static boolean matches(
      ProfileEntry entry,
      String uuid,
      String textureUrl,
      String textureHash,
      String textureIdPath) {
    return entry != null
        && Objects.equals(entry.uuid, uuid)
        && Objects.equals(entry.textureUrl, textureUrl)
        && Objects.equals(entry.textureHash, textureHash)
        && Objects.equals(entry.textureIdPath, textureIdPath);
  }

  // ── Persistence ──────────────────────────────────────────────────

  private static synchronized void loadIndex() {
    if (cacheFile == null || !Files.exists(cacheFile)) return;

    Type type = new TypeToken<ConcurrentHashMap<String, ProfileEntry>>() {}.getType();
    ConcurrentHashMap<String, ProfileEntry> loaded =
        ImfFileIO.readJson(cacheFile, GSON, type, SkinCacheMod.LOGGER, "SkinCache profile cache");
    if (loaded != null) {
      profiles.putAll(loaded);
    }
    SkinCacheMod.LOGGER.debug("[SkinCache] Loaded {} cached profiles", profiles.size());
  }

  private static void saveAsync() {
    SAVE_QUEUE.requestSave();
  }

  private static synchronized boolean saveIndex() {
    if (cacheFile == null) return true;

    // Clear before serialization. A cache hit that races with the write sets this back to true and
    // is persisted by a later batch rather than being accidentally forgotten.
    accessDirty.set(false);
    boolean saved =
        ImfFileIO.writeJsonAtomic(
            cacheFile, GSON, profiles, SkinCacheMod.LOGGER, "SkinCache profile cache");
    if (saved) {
      lastSuccessfulSaveMs = System.currentTimeMillis();
    } else {
      accessDirty.set(true);
    }
    return saved;
  }

  // ── Entry ────────────────────────────────────────────────────────

  public static class ProfileEntry {
    public String uuid;
    public String textureUrl;
    public String textureHash;
    public String textureIdPath; // e.g. "skins/abc123def..."
    public long timestamp; // epoch millis when first cached
    public long lastAccessed; // epoch millis of most recent get(); 0 = legacy entry
  }
}
