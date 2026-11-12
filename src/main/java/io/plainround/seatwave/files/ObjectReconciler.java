package io.plainround.seatwave.files;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares what the database says is stored ({@code stored_objects}) with what a bucket listing
 * shows, and sorts every difference into the action it needs. The database and the bucket cannot
 * share a transaction, so they drift; this job is how SeatWave finds out, rather than a fan.
 *
 * <p>Thread-safety: immutable and stateless between calls.
 */
public final class ObjectReconciler {

  /** The life of a row: issued a URL, bytes confirmed, or being removed. */
  public enum State {
    PENDING,
    STORED,
    DELETING
  }

  /** One row of {@code stored_objects}, as the job reads it. */
  public record Row(
      String objectKey, State state, String sha256Hex, long sizeBytes, Instant createdAt) {
    /** Rejects a missing key, state or time. */
    public Row {
      Objects.requireNonNull(objectKey, "objectKey");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(sha256Hex, "sha256Hex");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  /** One object from the bucket listing (an inventory report, at scale). */
  public record Listed(String objectKey, String sha256Hex, long sizeBytes, Instant lastModified) {
    /** Rejects a missing key, checksum or time. */
    public Listed {
      Objects.requireNonNull(objectKey, "objectKey");
      Objects.requireNonNull(sha256Hex, "sha256Hex");
      Objects.requireNonNull(lastModified, "lastModified");
    }
  }

  /**
   * What to do. {@code missing}: a STORED row whose bytes are gone, the invariant broken (page
   * someone). {@code mismatched}: bytes present but not the ones recorded. {@code orphansToDelete}:
   * bytes with no row, older than the grace period. {@code pendingToAbandon}: URLs issued long ago
   * and never confirmed. {@code deletesToFinish}: rows marked for deletion whose bytes must still
   * be removed.
   */
  public record Report(
      List<String> missing,
      List<String> mismatched,
      List<String> orphansToDelete,
      List<String> pendingToAbandon,
      List<String> deletesToFinish) {
    /** Copies every list, so a report cannot change after it is made. */
    public Report {
      missing = List.copyOf(missing);
      mismatched = List.copyOf(mismatched);
      orphansToDelete = List.copyOf(orphansToDelete);
      pendingToAbandon = List.copyOf(pendingToAbandon);
      deletesToFinish = List.copyOf(deletesToFinish);
    }
  }

  private final Duration grace;

  /**
   * @param grace how long an upload may be in flight between its PUT and its confirmation: at least
   *     the longest URL lifetime plus a margin, from configuration, so an upload is never mistaken
   *     for an orphan while its owner is still finishing it
   */
  public ObjectReconciler(Duration grace) {
    this.grace = Objects.requireNonNull(grace, "grace");
    if (grace.isNegative() || grace.isZero()) {
      throw new IllegalArgumentException("grace must be positive, was " + grace);
    }
  }

  /** Sorts every row and every listed object into at most one list of the report. */
  public Report reconcile(List<Row> rows, List<Listed> listing, Instant now) {
    Map<String, Listed> listedByKey =
        listing.stream().collect(Collectors.toMap(Listed::objectKey, Function.identity()));
    Map<String, Row> rowsByKey =
        rows.stream().collect(Collectors.toMap(Row::objectKey, Function.identity()));
    Instant cutoff = now.minus(grace);
    List<String> missing = new ArrayList<>();
    List<String> mismatched = new ArrayList<>();
    List<String> pendingToAbandon = new ArrayList<>();
    List<String> deletesToFinish = new ArrayList<>();
    for (Row row : rows) {
      Listed listed = listedByKey.get(row.objectKey());
      switch (row.state()) {
        case STORED -> {
          if (listed == null) {
            missing.add(row.objectKey());
          } else if (listed.sizeBytes() != row.sizeBytes()
              || !listed.sha256Hex().equals(row.sha256Hex())) {
            mismatched.add(row.objectKey());
          }
        }
        case PENDING -> {
          if (row.createdAt().isBefore(cutoff)) {
            pendingToAbandon.add(row.objectKey());
          }
        }
        case DELETING -> {
          if (listed != null) {
            deletesToFinish.add(row.objectKey());
          }
        }
      }
    }
    // An object with no row is deleted only once it is older than the grace period: a young one may
    // be
    // an upload whose confirmation is still on its way.
    List<String> orphansToDelete =
        listing.stream()
            .filter(listed -> !rowsByKey.containsKey(listed.objectKey()))
            .filter(listed -> listed.lastModified().isBefore(cutoff))
            .map(Listed::objectKey)
            .sorted()
            .toList();
    return new Report(missing, mismatched, orphansToDelete, pendingToAbandon, deletesToFinish);
  }
}
