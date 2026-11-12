package io.plainround.seatwave.files;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What one pre-signed URL allows: one method, on one object key in one bucket, until one instant,
 * and for an upload, one exact content type and length. A grant that breaks any rule cannot exist.
 *
 * @param method GET to download, PUT to upload
 * @param bucket the bucket name, lowercase letters, digits and hyphens
 * @param objectKey the object key, from an allow-list of characters, never with ".." segments
 * @param contentType for PUT, one of the allowed types; for GET, empty (a download sends no body)
 * @param contentLengthBytes for PUT, the exact body size, 1 to the largest single upload; for GET,
 *     0
 * @param expiresAt the instant after which the URL is refused
 */
public record UrlGrant(
    Method method,
    String bucket,
    String objectKey,
    String contentType,
    long contentLengthBytes,
    Instant expiresAt) {

  /** The two operations SeatWave ever pre-signs. */
  public enum Method {
    GET,
    PUT
  }

  /** Ticket PDFs and the two poster formats; anything else (HTML, SVG, scripts) is refused. */
  public static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("application/pdf", "image/jpeg", "image/png");

  /**
   * The largest body one PUT may carry here: a 20 MB poster, the brief's upper size. Larger files
   * use a multipart upload, whose parts are each granted separately.
   */
  public static final long MAX_SINGLE_UPLOAD_BYTES = 20_000_000L;

  // S3's rule for bucket names, narrowed: 3 to 63 characters, no dots, so a name is also a host
  // label.
  private static final Pattern BUCKET_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");
  // An allow-list instead of a deny-list: a key can never carry spaces, quotes, "%" or "?" into a
  // URL.
  private static final Pattern OBJECT_KEY = Pattern.compile("^[a-z0-9][a-z0-9/._-]{0,1023}$");

  /**
   * Rejects a missing field, a malformed name, a ".." segment, or a body rule broken for the
   * method.
   */
  public UrlGrant {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(bucket, "bucket");
    Objects.requireNonNull(objectKey, "objectKey");
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (!BUCKET_NAME.matcher(bucket).matches()) {
      throw new IllegalArgumentException("bucket name not allowed: " + bucket);
    }
    // A ".." segment could climb out of the prefix a grant was meant for in a file-backed store.
    if (!OBJECT_KEY.matcher(objectKey).matches() || objectKey.contains("..")) {
      throw new IllegalArgumentException("object key not allowed: " + objectKey);
    }
    switch (method) {
      case PUT -> requireUploadBody(contentType, contentLengthBytes);
      case GET -> requireNoBody(contentType, contentLengthBytes);
    }
  }

  /** A grant to upload exactly {@code contentLengthBytes} bytes of {@code contentType}. */
  public static UrlGrant upload(
      String bucket,
      String objectKey,
      String contentType,
      long contentLengthBytes,
      Instant expiresAt) {
    return new UrlGrant(Method.PUT, bucket, objectKey, contentType, contentLengthBytes, expiresAt);
  }

  /** A grant to download one object. */
  public static UrlGrant download(String bucket, String objectKey, Instant expiresAt) {
    return new UrlGrant(Method.GET, bucket, objectKey, "", 0, expiresAt);
  }

  private static void requireUploadBody(String contentType, long contentLengthBytes) {
    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new IllegalArgumentException("content type not allowed: " + contentType);
    }
    if (contentLengthBytes < 1 || contentLengthBytes > MAX_SINGLE_UPLOAD_BYTES) {
      throw new IllegalArgumentException(
          "content length must be 1 to "
              + MAX_SINGLE_UPLOAD_BYTES
              + " bytes, was "
              + contentLengthBytes);
    }
  }

  private static void requireNoBody(String contentType, long contentLengthBytes) {
    if (!contentType.isEmpty() || contentLengthBytes != 0) {
      throw new IllegalArgumentException("a GET grant carries no content type and no length");
    }
  }
}
