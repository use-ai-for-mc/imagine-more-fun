package com.chenweikeng.imf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts native helper binaries from the JAR to disk and reuses the cached copy across launches
 * only when its content hash matches the resource bundled in the current JAR. A sidecar file
 * (`<binary>.sha256`) records the hash of the cached binary so the next launch can detect a
 * version-update mismatch and re-extract without manual intervention.
 *
 * <p>Why a hash and not just the file's existence: the older code path used {@code
 * Files.isExecutable(userPath)} as the keep/extract decision, which meant a stale binary from a
 * previous mod version stuck around and was preferred over the freshly-bundled one in the JAR. A
 * `build-and-deploy.sh` had to wipe the cache dir to force re-extraction, which silently broke
 * whenever the cache lived under a different launcher's profile.
 */
public final class NativeHelperExtractor {
  private static final Logger LOGGER = LoggerFactory.getLogger("NativeHelperExtractor");

  private NativeHelperExtractor() {}

  /**
   * Returns the path to the helper binary on disk, re-extracting from the JAR if the cached copy is
   * missing or its content does not match the bundled resource. Returns {@code null} if the
   * resource is not in the JAR or the I/O fails.
   *
   * @param ownerClass any class loaded from the same JAR — used as the {@code getResourceAsStream}
   *     anchor.
   * @param resourcePath absolute resource path inside the JAR (e.g. {@code
   *     "/native/macos/webview-helper"}).
   * @param targetPath where to extract the binary on disk.
   * @param markExecutable whether to {@code chmod +x} the extracted file.
   */
  public static Path findOrExtract(
      Class<?> ownerClass, String resourcePath, Path targetPath, boolean markExecutable) {
    String jarHash;
    try (InputStream in = ownerClass.getResourceAsStream(resourcePath)) {
      if (in == null) {
        LOGGER.warn("Native helper resource not found in JAR: {}", resourcePath);
        return null;
      }
      jarHash = sha256(in);
    } catch (IOException e) {
      LOGGER.error("Failed to hash JAR resource {}: {}", resourcePath, e.getMessage());
      return null;
    }

    Path sidecar = targetPath.resolveSibling(targetPath.getFileName() + ".sha256");
    if (Files.isRegularFile(targetPath)
        && (!markExecutable || Files.isExecutable(targetPath))
        && Files.isRegularFile(sidecar)) {
      try {
        String cached = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
        if (cached.equals(jarHash)) {
          LOGGER.debug("Reusing cached native helper at {} (hash matches)", targetPath);
          return targetPath;
        }
        LOGGER.info(
            "Cached native helper at {} is stale (cached hash {} vs JAR {}), re-extracting",
            targetPath,
            cached.substring(0, Math.min(12, cached.length())),
            jarHash.substring(0, Math.min(12, jarHash.length())));
      } catch (IOException e) {
        LOGGER.warn("Failed to read sidecar {}: {} — treating as stale", sidecar, e.getMessage());
      }
    }

    try (InputStream in = ownerClass.getResourceAsStream(resourcePath)) {
      if (in == null) {
        return null;
      }
      Files.createDirectories(targetPath.getParent());
      Path tempPath =
          targetPath.resolveSibling(
              targetPath.getFileName() + ".tmp" + Thread.currentThread().threadId());
      try {
        Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
        if (markExecutable) {
          tempPath.toFile().setExecutable(true);
        }
        Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tempPath);
      }
      Files.writeString(sidecar, jarHash, StandardCharsets.UTF_8);
      LOGGER.debug(
          "Extracted native helper {} to {} (hash {})",
          resourcePath,
          targetPath,
          jarHash.substring(0, Math.min(12, jarHash.length())));
      return targetPath;
    } catch (IOException e) {
      LOGGER.error(
          "Failed to extract native helper {} to {}: {}", resourcePath, targetPath, e.getMessage());
      return null;
    }
  }

  private static String sha256(InputStream in) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 unavailable", e);
    }
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) {
      md.update(buf, 0, n);
    }
    byte[] digest = md.digest();
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
