package io.plainround.seatwave.files;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Uploads a 20,000,000-byte poster in 5 MiB parts with one part corrupted on its first trip, shows
 * that a completion with a missing part is refused and a correct one rebuilds the exact bytes, then
 * abandons a second upload halfway and lets a lifecycle rule remove it after 7 days.
 */
public final class MultipartDemoCli {

  // The brief's largest poster.
  private static final int POSTER_BYTES = 20_000_000;
  // Fixed so every run uploads the same bytes and prints the same checksums.
  private static final long POSTER_SEED = 29L;
  private static final int PART_BYTES = (int) MultipartUpload.MIN_PART_BYTES;
  private static final int CORRUPTED_PART = 3;
  private static final int CORRUPTED_BYTE_IN_PART = 1_000_000;
  // The lifecycle rule of lifecycle/seatwave-media.json: AbortIncompleteMultipartUpload after 7
  // days.
  private static final int ABORT_AFTER_DAYS = 7;
  private static final int ABANDONED_PARTS_SENT = 2;
  private static final int DAYS_WATCHED = 8;
  private static final Instant STARTED_AT = Instant.parse("2026-11-11T10:00:00Z");

  private MultipartDemoCli() {}

  /** Runs the upload, the refused and the accepted completion, and the abandoned upload. */
  public static void main(String[] arguments) {
    byte[] poster = new byte[POSTER_BYTES];
    new Random(POSTER_SEED).nextBytes(poster);
    List<byte[]> parts = split(poster);
    System.out.println(
        String.format(
            Locale.ROOT,
            "poster: %,d bytes, SHA-256 %s",
            poster.length,
            ContentAddress.sha256Hex(poster)));
    System.out.println(
        String.format(
            Locale.ROOT,
            "parts of %,d bytes (5 MiB): %d, the last %,d bytes",
            PART_BYTES,
            parts.size(),
            parts.getLast().length));

    MultipartUpload upload =
        MultipartUpload.initiate(
            "incoming/event-1/019a7310-0b7d-7e22-8c4a-5d1f9b6e2c03.jpg", STARTED_AT);
    List<MultipartUpload.PartReceipt> receipts = new ArrayList<>();
    long bytesSent = 0;
    for (int index = 0; index < parts.size(); index++) {
      int partNumber = index + 1;
      byte[] part = parts.get(index);
      // The client hashes the part before it leaves; the store hashes what arrives.
      String declared = ContentAddress.sha256Hex(part);
      byte[] onTheWire = part;
      if (partNumber == CORRUPTED_PART) {
        onTheWire = part.clone();
        onTheWire[CORRUPTED_BYTE_IN_PART] ^= 0b0000_0100;
        bytesSent += onTheWire.length;
        report(
            partNumber,
            "first try (one bit flipped in transit)",
            upload.uploadPart(partNumber, onTheWire, declared));
        onTheWire = part;
      }
      bytesSent += onTheWire.length;
      MultipartUpload.Outcome outcome = upload.uploadPart(partNumber, onTheWire, declared);
      report(partNumber, partNumber == CORRUPTED_PART ? "retried" : "sent", outcome);
      if (outcome instanceof MultipartUpload.Stored stored) {
        receipts.add(stored.receipt());
      }
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "bytes sent: %,d (one 5 MiB part twice), against %,d to resend a single PUT",
            bytesSent,
            2L * POSTER_BYTES));
    System.out.println(
        String.format(
            Locale.ROOT, "parts held, not yet an object: %,d bytes", upload.storedPartBytes()));

    List<MultipartUpload.PartReceipt> withoutPartTwo = new ArrayList<>(receipts);
    withoutPartTwo.remove(1);
    System.out.println(
        "complete listing parts 1, 3, 4: " + describe(upload.complete(withoutPartTwo)));
    MultipartUpload.Outcome completed = upload.complete(receipts);
    System.out.println("complete listing parts 1 to 4: " + describe(completed));
    if (completed instanceof MultipartUpload.Completed object) {
      System.out.println(
          "assembled bytes equal the poster: "
              + Arrays.equals(object.objectBytes(), poster)
              + ", SHA-256 "
              + ContentAddress.sha256Hex(object.objectBytes()));
    }
    System.out.println();
    abandonAndSweep(parts);
  }

  // A second upload whose client stops after two parts; a daily lifecycle sweep aborts it on day 7.
  private static void abandonAndSweep(List<byte[]> parts) {
    MultipartUpload abandoned =
        MultipartUpload.initiate(
            "incoming/event-1/019a7311-4f20-7a91-b3c6-0e8d2a7f5b19.jpg", STARTED_AT);
    for (int index = 0; index < ABANDONED_PARTS_SENT; index++) {
      abandoned.uploadPart(index + 1, parts.get(index), ContentAddress.sha256Hex(parts.get(index)));
    }
    System.out.println(
        "abandoned upload: client stopped after "
            + ABANDONED_PARTS_SENT
            + " parts; no object exists under its key");
    for (int day = 0; day <= DAYS_WATCHED; day++) {
      Instant sweepAt = STARTED_AT.plus(Duration.ofDays(day));
      boolean isDue =
          !sweepAt.isBefore(abandoned.startedAt().plus(Duration.ofDays(ABORT_AFTER_DAYS)));
      if (isDue && !abandoned.isFinished()) {
        abandoned.abort();
      }
      System.out.println(
          String.format(
              Locale.ROOT,
              "  day %d: parts held %,d bytes%s",
              day,
              abandoned.storedPartBytes(),
              isDue && day == ABORT_AFTER_DAYS ? "  (lifecycle rule aborted the upload)" : ""));
    }
  }

  private static List<byte[]> split(byte[] whole) {
    List<byte[]> parts = new ArrayList<>();
    for (int offset = 0; offset < whole.length; offset += PART_BYTES) {
      parts.add(Arrays.copyOfRange(whole, offset, Math.min(offset + PART_BYTES, whole.length)));
    }
    return parts;
  }

  private static void report(int partNumber, String label, MultipartUpload.Outcome outcome) {
    System.out.println(
        String.format(Locale.ROOT, "  part %d %-40s %s", partNumber, label, describe(outcome)));
  }

  private static String describe(MultipartUpload.Outcome outcome) {
    return switch (outcome) {
      case MultipartUpload.Stored stored ->
          "stored, checksum "
              + stored.receipt().sha256Hex().substring(0, ContentAddress.CDN_HEX_DIGITS)
              + "...";
      case MultipartUpload.Completed object ->
          String.format(
              Locale.ROOT,
              "COMPLETED, %,d bytes, composite checksum %s...-%s",
              object.objectBytes().length,
              object.compositeSha256Hex().substring(0, ContentAddress.CDN_HEX_DIGITS),
              object
                  .compositeSha256Hex()
                  .substring(object.compositeSha256Hex().lastIndexOf('-') + 1));
      case MultipartUpload.Refused refused -> "REFUSED, " + refused.reason();
    };
  }
}
