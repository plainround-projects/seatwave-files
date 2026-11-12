package io.plainround.seatwave.files;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A teaching model of a pre-signed URL: the storage side and SeatWave share one secret key;
 * SeatWave signs, with HMAC-SHA-256, the method, the bucket and key, the expiry and the content
 * rule of a grant, and anyone holding the URL may use it until it expires. It is not AWS Signature
 * Version 4 (which derives a per-day, per-region signing key and signs a canonical request), but it
 * refuses the same four things for the same reasons.
 *
 * <p>Thread-safety: immutable; a fresh {@link Mac} is created per call because a Mac is not.
 */
public final class PresignedUrlSigner {

  /** What a request presents to the storage side: its method, the URL, and its body's headers. */
  public record Request(
      UrlGrant.Method method, String url, String contentType, long contentLengthBytes) {
    /** Rejects a missing method, URL or content type (use "" for a request with no body). */
    public Request {
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(contentType, "contentType");
    }
  }

  // 256 bits, the HMAC-SHA-256 output size: a shorter key would be the weakest part of the scheme.
  private static final int MINIMUM_KEY_BYTES = 32;
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  // A version tag at the start of the signed text, so a future scheme can never verify as this one.
  private static final String SCHEME = "SW1-HMAC-SHA256";
  private static final String EXPIRES = "X-Sw-Expires";
  private static final String CONTENT_TYPE = "X-Sw-Content-Type";
  private static final String CONTENT_LENGTH = "X-Sw-Content-Length";
  private static final String SIGNATURE = "X-Sw-Signature";

  private final byte[] signingKey;
  private final Clock clock;
  private final Duration maxLifetime;
  private final String endpoint;

  /**
   * @param signingKey the shared secret, at least 32 bytes, copied on the way in
   * @param clock the source of "now" for signing and for checking expiry
   * @param maxLifetime the longest lifetime this signer will grant, from configuration
   * @param endpoint the storage endpoint, for example {@code https://files.seatwave.example}
   */
  public PresignedUrlSigner(byte[] signingKey, Clock clock, Duration maxLifetime, String endpoint) {
    Objects.requireNonNull(signingKey, "signingKey");
    if (signingKey.length < MINIMUM_KEY_BYTES) {
      throw new IllegalArgumentException(
          "signing key must be at least " + MINIMUM_KEY_BYTES + " bytes");
    }
    this.signingKey = Arrays.copyOf(signingKey, signingKey.length);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maxLifetime = Objects.requireNonNull(maxLifetime, "maxLifetime");
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    if (maxLifetime.isNegative() || maxLifetime.isZero()) {
      throw new IllegalArgumentException("maximum lifetime must be positive, was " + maxLifetime);
    }
  }

  /** The same key, lifetime and endpoint, reading time from another clock. */
  public PresignedUrlSigner withClock(Clock otherClock) {
    return new PresignedUrlSigner(signingKey, otherClock, maxLifetime, endpoint);
  }

  /**
   * Signs a grant and returns the URL.
   *
   * @throws IllegalArgumentException if the grant has already expired or lives longer than the
   *     configured maximum: both are bugs in the caller, which computes the expiry from settings
   */
  public String sign(UrlGrant grant) {
    Instant now = clock.instant();
    if (!grant.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException("grant already expired at " + grant.expiresAt());
    }
    Duration lifetime = Duration.between(now, grant.expiresAt());
    if (lifetime.compareTo(maxLifetime) > 0) {
      throw new IllegalArgumentException(
          "lifetime " + lifetime + " exceeds the maximum of " + maxLifetime);
    }
    long expiresEpochSecond = grant.expiresAt().getEpochSecond();
    String signature =
        signatureFor(
            grant.method(),
            grant.bucket(),
            grant.objectKey(),
            expiresEpochSecond,
            grant.contentType(),
            grant.contentLengthBytes());
    StringBuilder url = new StringBuilder();
    url.append(endpoint).append('/').append(grant.bucket()).append('/').append(grant.objectKey());
    url.append('?').append(EXPIRES).append('=').append(expiresEpochSecond);
    if (grant.method() == UrlGrant.Method.PUT) {
      // The content rule travels in the URL, signed, the way a POST policy carries its conditions.
      url.append('&').append(CONTENT_TYPE).append('=').append(encode(grant.contentType()));
      url.append('&').append(CONTENT_LENGTH).append('=').append(grant.contentLengthBytes());
    }
    url.append('&').append(SIGNATURE).append('=').append(signature);
    return url.toString();
  }

  /**
   * Checks a request in the order a storage service must: is the URL well formed, was every signed
   * part signed by the key, is it still within its lifetime, does the body obey the content rule.
   * The signature is checked before the expiry so that an edited expiry reads as a forgery.
   */
  public UrlVerdict verify(Request request) {
    Optional<ParsedUrl> parsed = parse(request.url());
    if (parsed.isEmpty()) {
      return new UrlVerdict.Refused(
          UrlVerdict.Reason.MALFORMED, "not a pre-signed URL: missing parts");
    }
    ParsedUrl url = parsed.get();
    String expected =
        signatureFor(
            request.method(),
            url.bucket(),
            url.objectKey(),
            url.expiresEpochSecond(),
            url.contentType(),
            url.contentLengthBytes());
    // A constant-time comparison, so response timing never reveals how many leading digits matched.
    boolean genuine =
        MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            url.signature().getBytes(StandardCharsets.US_ASCII));
    if (!genuine) {
      return new UrlVerdict.Refused(
          UrlVerdict.Reason.BAD_SIGNATURE,
          "signature does not match " + request.method() + " " + url.path());
    }
    Instant expiresAt = Instant.ofEpochSecond(url.expiresEpochSecond());
    Instant now = clock.instant();
    if (!now.isBefore(expiresAt)) {
      return new UrlVerdict.Refused(
          UrlVerdict.Reason.EXPIRED,
          "expired " + Duration.between(expiresAt, now).toSeconds() + " s ago");
    }
    if (request.method() == UrlGrant.Method.PUT
        && (!request.contentType().equals(url.contentType())
            || request.contentLengthBytes() != url.contentLengthBytes())) {
      return new UrlVerdict.Refused(
          UrlVerdict.Reason.CONTENT_MISMATCH,
          String.format(
              Locale.ROOT,
              "signed %s of %d bytes, sent %s of %d bytes",
              url.contentType(),
              url.contentLengthBytes(),
              request.contentType(),
              request.contentLengthBytes()));
    }
    return new UrlVerdict.Accepted(
        new UrlGrant(
            request.method(),
            url.bucket(),
            url.objectKey(),
            url.contentType(),
            url.contentLengthBytes(),
            expiresAt));
  }

  /** The text that is signed, one field per line, so no two different grants share one text. */
  static String stringToSign(
      UrlGrant.Method method,
      String bucket,
      String objectKey,
      long expiresEpochSecond,
      String contentType,
      long contentLengthBytes) {
    return String.join(
        "\n",
        SCHEME,
        method.name(),
        "/" + bucket + "/" + objectKey,
        Long.toString(expiresEpochSecond),
        contentType,
        Long.toString(contentLengthBytes));
  }

  private String signatureFor(
      UrlGrant.Method method,
      String bucket,
      String objectKey,
      long expiresEpochSecond,
      String contentType,
      long contentLengthBytes) {
    String text =
        stringToSign(
            method, bucket, objectKey, expiresEpochSecond, contentType, contentLengthBytes);
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException missingAlgorithm) {
      // Every JDK must provide HmacSHA256, so this is a broken runtime, not a bad request.
      throw new IllegalStateException("HMAC-SHA-256 unavailable", missingAlgorithm);
    }
  }

  private Optional<ParsedUrl> parse(String text) {
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException notAUrl) {
      return Optional.empty();
    }
    String path = uri.getRawPath();
    if (path == null || uri.getRawQuery() == null) {
      return Optional.empty();
    }
    int keyStart = path.indexOf('/', 1);
    if (!path.startsWith("/") || keyStart < 0 || keyStart == path.length() - 1) {
      return Optional.empty();
    }
    Map<String, String> query = new HashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0) {
        query.put(
            pair.substring(0, equals),
            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    if (!query.containsKey(EXPIRES) || !query.containsKey(SIGNATURE)) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new ParsedUrl(
              path,
              path.substring(1, keyStart),
              path.substring(keyStart + 1),
              Long.parseLong(query.get(EXPIRES)),
              query.getOrDefault(CONTENT_TYPE, ""),
              Long.parseLong(query.getOrDefault(CONTENT_LENGTH, "0")),
              query.get(SIGNATURE)));
    } catch (NumberFormatException notANumber) {
      return Optional.empty();
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record ParsedUrl(
      String path,
      String bucket,
      String objectKey,
      long expiresEpochSecond,
      String contentType,
      long contentLengthBytes,
      String signature) {}
}
