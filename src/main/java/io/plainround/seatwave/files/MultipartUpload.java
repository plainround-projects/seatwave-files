package io.plainround.seatwave.files;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A model of one multipart upload as an object store keeps it: parts arrive independently, each is
 * checked against the checksum its sender declared, a part sent again replaces the earlier one, and
 * nothing becomes an object until the client completes the upload with its own list of parts. Until
 * then the parts are invisible to listings but take space, and are billed.
 *
 * <p>Thread-safety: not thread-safe; one upload is driven by one caller, as each S3 upload id is.
 */
public final class MultipartUpload {

  /** What the store answers for one part, and what the client must keep to complete the upload. */
  public record PartReceipt(int partNumber, String sha256Hex, long sizeBytes) {
    /** Rejects a part number outside 1 to 10,000 or a malformed checksum. */
    public PartReceipt {
      if (partNumber < 1 || partNumber > MAX_PARTS) {
        throw new IllegalArgumentException(
            "part number must be 1 to " + MAX_PARTS + ", was " + partNumber);
      }
      Objects.requireNonNull(sha256Hex, "sha256Hex");
      if (!sha256Hex.matches("^[0-9a-f]{64}$")) {
        throw new IllegalArgumentException("not a SHA-256 in hex: " + sha256Hex);
      }
    }
  }

  /** The outcome of sending a part or completing the upload. */
  public sealed interface Outcome {}

  /** The part was stored; keep the receipt. */
  public record Stored(PartReceipt receipt) implements Outcome {}

  /** The object now exists, with these bytes and this checksum of the part checksums. */
  public record Completed(byte[] objectBytes, String compositeSha256Hex) implements Outcome {}

  /** Nothing changed; {@code reason} names the rule that was broken. */
  public record Refused(String reason) implements Outcome {}

  /**
   * S3's documented limits (checked 11 November 2026): parts 1 to 10,000, each 5 MiB or more but
   * the last.
   */
  public static final int MAX_PARTS = 10_000;

  public static final long MIN_PART_BYTES = 5L * 1024 * 1024;

  private final String objectKey;
  private final Instant startedAt;
  private final Map<Integer, byte[]> partsByNumber = new TreeMap<>();
  private boolean isFinished;

  private MultipartUpload(String objectKey, Instant startedAt) {
    this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
  }

  /** Starts an upload for one key; the store would answer with an upload id. */
  public static MultipartUpload initiate(String objectKey, Instant startedAt) {
    return new MultipartUpload(objectKey, startedAt);
  }

  /**
   * Stores one part if its bytes match the checksum the sender computed before sending; a mismatch
   * means the bytes changed on the way, and the part is refused so the sender sends it again.
   */
  public Outcome uploadPart(int partNumber, byte[] partBytes, String declaredSha256Hex) {
    if (isFinished) {
      return new Refused("upload already completed or aborted");
    }
    if (partNumber < 1 || partNumber > MAX_PARTS) {
      return new Refused("part number must be 1 to " + MAX_PARTS);
    }
    String receivedSha256Hex = ContentAddress.sha256Hex(partBytes);
    if (!receivedSha256Hex.equals(declaredSha256Hex)) {
      return new Refused(
          "part " + partNumber + ": checksum of received bytes differs from the declared one");
    }
    // Same part number again: the newer bytes replace the older, as in S3.
    partsByNumber.put(partNumber, partBytes.clone());
    return new Stored(new PartReceipt(partNumber, receivedSha256Hex, partBytes.length));
  }

  /**
   * Assembles the object from the client's list, which must name parts 1 to n in order, each with
   * the checksum the store returned for it, every part but the last at least 5 MiB.
   */
  public Outcome complete(List<PartReceipt> receipts) {
    if (isFinished) {
      return new Refused("upload already completed or aborted");
    }
    if (receipts.isEmpty()) {
      return new Refused("no parts listed");
    }
    ByteArrayOutputStream assembled = new ByteArrayOutputStream();
    ByteArrayOutputStream partDigests = new ByteArrayOutputStream();
    for (int index = 0; index < receipts.size(); index++) {
      PartReceipt receipt = receipts.get(index);
      // With checksums, S3 requires consecutive part numbers from 1; a gap means a lost part.
      if (receipt.partNumber() != index + 1) {
        return new Refused(
            "parts must be 1 to n with no gap; position "
                + (index + 1)
                + " lists part "
                + receipt.partNumber());
      }
      byte[] stored = partsByNumber.get(receipt.partNumber());
      if (stored == null || !ContentAddress.sha256Hex(stored).equals(receipt.sha256Hex())) {
        return new Refused("part " + receipt.partNumber() + " is not stored with that checksum");
      }
      boolean isLast = index == receipts.size() - 1;
      if (!isLast && stored.length < MIN_PART_BYTES) {
        return new Refused(
            "part " + receipt.partNumber() + " is under 5 MiB and is not the last part");
      }
      assembled.writeBytes(stored);
      partDigests.writeBytes(ContentAddress.sha256(stored));
    }
    isFinished = true;
    partsByNumber.clear();
    // A checksum of checksums, written "-n" like S3's composite form, so it is never mistaken for
    // the whole file's.
    String composite = ContentAddress.sha256Hex(partDigests.toByteArray()) + "-" + receipts.size();
    return new Completed(assembled.toByteArray(), composite);
  }

  /** Discards every part; what a lifecycle rule for incomplete uploads does when its time comes. */
  public void abort() {
    isFinished = true;
    partsByNumber.clear();
  }

  /** Bytes held for parts not yet assembled: invisible in a listing of objects, but billed. */
  public long storedPartBytes() {
    return partsByNumber.values().stream().mapToLong(part -> part.length).sum();
  }

  /** The key this upload will create. */
  public String objectKey() {
    return objectKey;
  }

  /** When the upload began; lifecycle rules count days from here. */
  public Instant startedAt() {
    return startedAt;
  }

  /** Whether it was completed or aborted. */
  public boolean isFinished() {
    return isFinished;
  }
}
