package com.chenweikeng.imf.skincache.cache;

import com.chenweikeng.imf.skincache.SkinCacheMod;
import com.chenweikeng.imf.skincache.util.TextureValidator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;

/**
 * File-based texture cache with two-tier eviction (LRU + TTL).
 *
 * <p>Layout on disk: .minecraft/skincache/ index.json — maps texture URL -> CacheEntry (hash,
 * timestamp, lastAccessed) textures/ <sha256>.png — the cached skin file
 *
 * <p>Eviction policy: - Hard TTL: any entry whose lastAccessed is older than {@link
 * #DEFAULT_TTL_MS} is dropped. - Capacity LRU: when the index has more than {@link
 * #MAX_CACHE_FILES} entries, the oldest-accessed entries are dropped until back under the cap.
 *
 * <p>Access tracking: - {@code lastAccessed} is updated on cache hits, but only at coarse
 * granularity (no more than once per {@link #ACCESS_UPDATE_GRANULARITY_MS}). - Updates are buffered
 * in memory and flushed by a periodic save thread that runs every {@link #PERIODIC_INTERVAL_SEC}
 * seconds, plus a JVM shutdown hook for final persistence.
 *
 * <p>Thread safety: the in-memory index is a ConcurrentHashMap; disk writes are synchronised on the
 * class lock. Reads are lock-free.
 */
public final class TextureCache {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /** Hard TTL: entries unaccessed for longer than this are evicted. */
  private static final long DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000L; // 30 days

  /** Capacity cap. When exceeded, lowest-{@code lastAccessed} entries are evicted first. */
  private static final long MAX_CACHE_FILES = 10_000;

  /**
   * Coarse-grained access tracking: only update {@code lastAccessed} if at least this much elapsed.
   */
  private static final long ACCESS_UPDATE_GRANULARITY_MS = 5 * 60 * 1000L; // 5 min

  /** Periodic maintenance interval (save dirty access tracking + run eviction). */
  private static final long PERIODIC_INTERVAL_SEC = 60;

  private static Path cacheDir;
  private static Path texturesDir;
  private static Path indexFile;

  private static final ConcurrentHashMap<String, CacheEntry> index = new ConcurrentHashMap<>();
  private static final ScheduledExecutorService SAVE_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "skincache-save");
            t.setDaemon(true);
            return t;
          });

  /** Set by put/evict; triggers a near-immediate coalesced save via {@link #saveIndexAsync()}. */
  private static final AtomicBoolean saveDirty = new AtomicBoolean(false);

  /** Set by access-time bumps; flushed only by the periodic maintenance thread. */
  private static final AtomicBoolean accessDirty = new AtomicBoolean(false);

  // ── Initialisation ─────────────────────────────────────────────

  public static void init() {
    // Use FabricLoader to resolve the actual .minecraft game directory
    Path gameDir = FabricLoader.getInstance().getGameDir();
    cacheDir = gameDir.resolve("skincache");
    texturesDir = cacheDir.resolve("textures");
    indexFile = cacheDir.resolve("index.json");

    try {
      Files.createDirectories(texturesDir);
      loadIndex();
      evictExpired();
    } catch (IOException e) {
      SkinCacheMod.LOGGER.error("[SkinCache] Failed to initialise cache directory", e);
    }

    // Periodic maintenance: flush access updates and run eviction
    SAVE_EXECUTOR.scheduleAtFixedRate(
        TextureCache::periodicMaintenance,
        PERIODIC_INTERVAL_SEC,
        PERIODIC_INTERVAL_SEC,
        TimeUnit.SECONDS);

    // Final save on JVM shutdown
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    saveIndex();
                  } catch (Exception e) {
                    SkinCacheMod.LOGGER.warn("[SkinCache] shutdown saveIndex failed", e);
                  }
                },
                "skincache-shutdown"));
  }

  public static Path getCacheDir() {
    return cacheDir;
  }

  // ── Public API ─────────────────────────────────────────────────

  /**
   * Look up a cached texture by its URL. Returns the path to the cached PNG if it exists and hasn't
   * expired. Updates {@code lastAccessed} (coarse-bucket) on hits.
   */
  public static Optional<Path> get(String textureUrl) {
    CacheEntry entry = index.get(textureUrl);
    if (entry == null) return Optional.empty();

    long now = System.currentTimeMillis();
    long lastAccess = effectiveLastAccessed(entry);

    // TTL is now measured against lastAccessed, not creation timestamp
    if (now - lastAccess > DEFAULT_TTL_MS) {
      SkinCacheMod.LOGGER.debug("[SkinCache] Cache expired for {}", textureUrl);
      index.remove(textureUrl);
      saveIndexAsync();
      return Optional.empty();
    }

    Path file = texturesDir.resolve(entry.hash + ".png");
    if (!Files.exists(file)) {
      SkinCacheMod.LOGGER.warn("[SkinCache] Index entry exists but file missing: {}", file);
      index.remove(textureUrl);
      saveIndexAsync();
      return Optional.empty();
    }

    bumpAccess(entry, now, lastAccess);

    SkinCacheMod.LOGGER.debug("[SkinCache] Cache HIT for {}", textureUrl);
    return Optional.of(file);
  }

  /**
   * Store a downloaded texture in the cache. Validates the texture BEFORE writing. Returns true if
   * stored successfully.
   */
  public static boolean put(String textureUrl, byte[] data) {
    // Validate before caching
    if (!TextureValidator.isValid(data)) {
      SkinCacheMod.LOGGER.warn("[SkinCache] Rejecting invalid texture for URL: {}", textureUrl);
      return false;
    }

    String hash = sha256(data);
    Path targetFile = texturesDir.resolve(hash + ".png");

    try {
      Files.write(
          targetFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

      long now = System.currentTimeMillis();
      CacheEntry entry = new CacheEntry();
      entry.hash = hash;
      entry.timestamp = now;
      entry.lastAccessed = now;
      entry.url = textureUrl;
      index.put(textureUrl, entry);

      saveIndexAsync();

      SkinCacheMod.LOGGER.debug("[SkinCache] Cached texture {} -> {}", textureUrl, hash);
      return true;
    } catch (IOException e) {
      SkinCacheMod.LOGGER.error("[SkinCache] Failed to write cache file", e);
      return false;
    }
  }

  /** Check if a URL is already cached (non-expired). */
  public static boolean contains(String textureUrl) {
    return get(textureUrl).isPresent();
  }

  /**
   * Lightweight presence check — no file I/O. Updates {@code lastAccessed} (coarse-bucket). Used by
   * the render-path mixins on the per-frame hot path.
   */
  public static boolean isCached(String textureUrl) {
    CacheEntry entry = index.get(textureUrl);
    if (entry == null) return false;

    long now = System.currentTimeMillis();
    long lastAccess = effectiveLastAccessed(entry);
    if (now - lastAccess > DEFAULT_TTL_MS) return false;

    bumpAccess(entry, now, lastAccess);
    return true;
  }

  // ── Cache maintenance ──────────────────────────────────────────

  /**
   * TTL eviction: remove entries whose {@code lastAccessed} is older than {@link #DEFAULT_TTL_MS}.
   */
  public static void evictExpired() {
    long now = System.currentTimeMillis();
    int removed = 0;

    Iterator<Map.Entry<String, CacheEntry>> it = index.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, CacheEntry> mapEntry = it.next();
      CacheEntry e = mapEntry.getValue();
      if (now - effectiveLastAccessed(e) > DEFAULT_TTL_MS) {
        Path file = texturesDir.resolve(e.hash + ".png");
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
        it.remove();
        removed++;
      }
    }

    if (removed > 0) {
      SkinCacheMod.LOGGER.debug("[SkinCache] Evicted {} expired entries", removed);
      saveIndexAsync();
    }
  }

  /**
   * Capacity eviction: if over {@link #MAX_CACHE_FILES}, drop oldest-accessed entries first (LRU).
   */
  public static void evictOverflow() {
    if (index.size() <= MAX_CACHE_FILES) return;

    index.entrySet().stream()
        .sorted(
            Map.Entry.comparingByValue(
                (a, b) -> Long.compare(effectiveLastAccessed(a), effectiveLastAccessed(b))))
        .limit(index.size() - MAX_CACHE_FILES)
        .forEach(
            entry -> {
              Path file = texturesDir.resolve(entry.getValue().hash + ".png");
              try {
                Files.deleteIfExists(file);
              } catch (IOException ignored) {
              }
              index.remove(entry.getKey());
            });

    saveIndexAsync();
  }

  /** Periodic maintenance task: flush dirty access tracking and re-run eviction. */
  private static void periodicMaintenance() {
    try {
      evictExpired();
      evictOverflow();
      // If only access-time bumps have happened since the last save, flush them now
      if (accessDirty.compareAndSet(true, false) && !saveDirty.get()) {
        saveIndex();
      }
    } catch (Exception e) {
      SkinCacheMod.LOGGER.warn("[SkinCache] Periodic maintenance failed", e);
    }
  }

  // ── Access tracking helpers ────────────────────────────────────

  /** Returns {@code lastAccessed}, falling back to {@code timestamp} for legacy entries. */
  private static long effectiveLastAccessed(CacheEntry e) {
    return e.lastAccessed > 0 ? e.lastAccessed : e.timestamp;
  }

  /**
   * Coarse-bucket update of {@code lastAccessed}: only writes if more than {@link
   * #ACCESS_UPDATE_GRANULARITY_MS} has elapsed since the previous bump. Marks {@code accessDirty}
   * so the next periodic flush picks it up.
   */
  private static void bumpAccess(CacheEntry entry, long now, long lastAccess) {
    if (now - lastAccess > ACCESS_UPDATE_GRANULARITY_MS) {
      entry.lastAccessed = now;
      accessDirty.set(true);
    }
  }

  // ── Index persistence ──────────────────────────────────────────

  private static synchronized void loadIndex() {
    if (!Files.exists(indexFile)) return;

    try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
      Type type = new TypeToken<ConcurrentHashMap<String, CacheEntry>>() {}.getType();
      ConcurrentHashMap<String, CacheEntry> loaded = GSON.fromJson(reader, type);
      if (loaded != null) {
        index.putAll(loaded);
      }
      SkinCacheMod.LOGGER.debug("[SkinCache] Loaded {} cached entries from index", index.size());
    } catch (Exception e) {
      SkinCacheMod.LOGGER.error("[SkinCache] Failed to load index, starting fresh", e);
    }
  }

  private static void saveIndexAsync() {
    // Coalesce rapid saves: only submit a new task if one isn't already queued
    if (saveDirty.compareAndSet(false, true)) {
      SAVE_EXECUTOR.execute(
          () -> {
            saveDirty.set(false);
            // saveIndex implicitly persists any pending access-bumps too,
            // so clear that flag to avoid an immediately-following redundant save
            accessDirty.set(false);
            saveIndex();
          });
    }
  }

  private static synchronized void saveIndex() {
    try (Writer writer =
        Files.newBufferedWriter(
            indexFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      GSON.toJson(index, writer);
    } catch (IOException e) {
      SkinCacheMod.LOGGER.error("[SkinCache] Failed to save index", e);
    }
  }

  // ── Utilities ──────────────────────────────────────────────────

  private static String sha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  // ── Cache entry record ─────────────────────────────────────────

  public static class CacheEntry {
    public String hash; // SHA-256 of the PNG bytes
    public long timestamp; // epoch millis when first cached (preserved for diagnostics)
    public long
        lastAccessed; // epoch millis of most recent cache hit; 0 = legacy entry, fall back to
    // timestamp
    public String url; // original texture URL
  }
}
