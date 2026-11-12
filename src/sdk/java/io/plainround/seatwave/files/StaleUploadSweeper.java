package io.plainround.seatwave.files;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Aborts multipart uploads that were started long ago and never completed, whose parts are
 * invisible in a listing but take space and are billed. S3 does this by lifecycle rule ({@code
 * AbortIncompleteMultipartUpload}); this sweep is the same decision made on demand, which the lab
 * needs because it cannot wait 7 days for the store's own rule.
 *
 * <p>Thread-safety: immutable; the store it is given must be thread-safe.
 */
public final class StaleUploadSweeper {

  /** One incomplete upload as a listing reports it. */
  public record OpenUpload(String objectKey, String uploadId, Instant initiated) {
    /** Rejects a missing key, id or time. */
    public OpenUpload {
      Objects.requireNonNull(objectKey, "objectKey");
      Objects.requireNonNull(uploadId, "uploadId");
      Objects.requireNonNull(initiated, "initiated");
    }
  }

  private final Duration maxAge;

  /**
   * @param maxAge how long an upload may stay open before it counts as abandoned; 7 days, the rule
   *     of {@code lifecycle/seatwave-media.json}, far longer than any real upload
   */
  public StaleUploadSweeper(Duration maxAge) {
    this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
    if (maxAge.isNegative() || maxAge.isZero()) {
      throw new IllegalArgumentException("maximum age must be positive, was " + maxAge);
    }
  }

  /** The uploads started at least {@code maxAge} before {@code now}; younger ones may finish. */
  public List<OpenUpload> stale(List<OpenUpload> open, Instant now) {
    Instant cutoff = now.minus(maxAge);
    return open.stream().filter(upload -> !upload.initiated().isAfter(cutoff)).toList();
  }

  /** The dry run: the bucket's stale uploads, with nothing changed. */
  public List<OpenUpload> findStale(ObjectStore store, String bucket, Instant now) {
    return stale(store.incompleteUploads(bucket), now);
  }

  /**
   * Aborts the bucket's stale uploads, which discards their parts.
   *
   * @return the uploads aborted
   */
  public List<OpenUpload> abortStale(ObjectStore store, String bucket, Instant now) {
    List<OpenUpload> stale = findStale(store, bucket, now);
    stale.forEach(upload -> store.abort(bucket, upload));
    return stale;
  }
}
