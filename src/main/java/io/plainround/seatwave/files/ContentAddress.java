package io.plainround.seatwave.files;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Names an object by the SHA-256 of its bytes. The same bytes always get the same key, different
 * bytes (in practice) never do, and anyone holding the key can check the bytes they received.
 *
 * <p>Thread-safety: stateless; a fresh digest is made per call.
 */
public final class ContentAddress {

  // Module 17 names CDN assets with the first 12 hex digits (48 bits) of the same digest.
  public static final int CDN_HEX_DIGITS = 12;
  private static final Pattern EXTENSION = Pattern.compile("^(pdf|jpg|png)$");
  private static final Pattern PREFIX = Pattern.compile("^[a-z0-9][a-z0-9/_-]*$");

  private ContentAddress() {}

  /** The full digest, 32 bytes. */
  public static byte[] sha256(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      // Every JDK must provide SHA-256, so this is a broken runtime, not bad input.
      throw new IllegalStateException("SHA-256 unavailable", missingAlgorithm);
    }
  }

  /** The digest as 64 lowercase hex digits. */
  public static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha256(bytes));
  }

  /**
   * The bucket key {@code <prefix>/<64 hex digits>.<extension>}, for example {@code
   * posters/1/....jpg}.
   */
  public static String keyFor(String prefix, byte[] bytes, String extension) {
    if (!PREFIX.matcher(prefix).matches() || prefix.endsWith("/")) {
      throw new IllegalArgumentException("prefix not allowed: " + prefix);
    }
    if (!EXTENSION.matcher(extension).matches()) {
      throw new IllegalArgumentException("extension not allowed: " + extension);
    }
    return prefix + "/" + sha256Hex(bytes) + "." + extension;
  }

  /** Module 17's CDN name, {@code <name>.<first 12 hex digits>.<extension>}. */
  public static String cdnName(String name, byte[] bytes, String extension) {
    return name + "." + sha256Hex(bytes).substring(0, CDN_HEX_DIGITS) + "." + extension;
  }

  /**
   * Whether these bytes are the ones the key names: the check a reader runs after every download.
   */
  public static boolean matches(String key, byte[] bytes) {
    int slash = key.lastIndexOf('/');
    int dot = key.lastIndexOf('.');
    if (slash < 0 || dot <= slash) {
      return false;
    }
    return key.substring(slash + 1, dot).equals(sha256Hex(bytes));
  }
}
