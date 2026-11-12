package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Nothing becomes an object until a complete, gap-free list of checked parts arrives. */
class MultipartUploadTest {

  private static final Instant STARTED_AT = Instant.parse("2026-11-12T10:00:00Z");
  private static final String KEY = "incoming/event-1/a.jpg";
  private static final int PART_BYTES = (int) MultipartUpload.MIN_PART_BYTES;
  private static final int LAST_PART_BYTES = 1_000;
  private static final byte FIRST_FILL = 1;
  private static final byte SECOND_FILL = 2;

  private final MultipartUpload upload = MultipartUpload.initiate(KEY, STARTED_AT);

  @Test
  void refusesAPartWhoseBytesDifferFromTheDeclaredChecksum() {
    byte[] part = filled(PART_BYTES, FIRST_FILL);
    byte[] damaged = part.clone();
    damaged[0] ^= 1;

    var outcome = upload.uploadPart(1, damaged, ContentAddress.sha256Hex(part));

    assertThat(outcome).isInstanceOf(MultipartUpload.Refused.class);
    assertThat(upload.storedPartBytes()).isZero();
  }

  @Test
  void assemblesTheExactBytesFromAGapFreeList() {
    byte[] first = filled(PART_BYTES, FIRST_FILL);
    byte[] last = filled(LAST_PART_BYTES, SECOND_FILL);

    var completed = upload.complete(List.of(stored(1, first), stored(2, last)));

    assertThat(completed)
        .isInstanceOfSatisfying(
            MultipartUpload.Completed.class,
            object -> {
              assertThat(object.objectBytes()).hasSize(PART_BYTES + LAST_PART_BYTES);
              assertThat(object.compositeSha256Hex()).endsWith("-2");
            });
    assertThat(upload.isFinished()).isTrue();
  }

  @Test
  void refusesACompletionThatSkipsAPartNumber() {
    var first = stored(1, filled(PART_BYTES, FIRST_FILL));
    var third = stored(3, filled(LAST_PART_BYTES, SECOND_FILL));

    var outcome = upload.complete(List.of(first, third));

    assertThat(outcome)
        .isEqualTo(
            new MultipartUpload.Refused(
                "parts must be 1 to n with no gap; position 2 lists part 3"));
    assertThat(upload.isFinished()).isFalse();
  }

  @Test
  void refusesAMiddlePartUnderFiveMebibytes() {
    var small = stored(1, filled(LAST_PART_BYTES, FIRST_FILL));
    var last = stored(2, filled(LAST_PART_BYTES, SECOND_FILL));

    var outcome = upload.complete(List.of(small, last));

    assertThat(outcome)
        .isEqualTo(new MultipartUpload.Refused("part 1 is under 5 MiB and is not the last part"));
  }

  @Test
  void letsAPartSentAgainReplaceTheEarlierOne() {
    stored(1, filled(LAST_PART_BYTES, FIRST_FILL));
    var again = stored(1, filled(LAST_PART_BYTES, SECOND_FILL));

    var completed = upload.complete(List.of(again));

    assertThat(completed)
        .isInstanceOfSatisfying(
            MultipartUpload.Completed.class,
            object -> assertThat(object.objectBytes()).containsOnly(SECOND_FILL));
  }

  @Test
  void discardsEveryPartWhenAborted() {
    stored(1, filled(PART_BYTES, FIRST_FILL));

    upload.abort();

    assertThat(upload.storedPartBytes()).isZero();
    assertThat(upload.uploadPart(2, new byte[1], ContentAddress.sha256Hex(new byte[1])))
        .isEqualTo(new MultipartUpload.Refused("upload already completed or aborted"));
  }

  @Test
  void rejectsAReceiptForAPartNumberAboveTenThousand() {
    assertThatThrownBy(
            () -> new MultipartUpload.PartReceipt(MultipartUpload.MAX_PARTS + 1, "a".repeat(64), 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("part number must be 1 to 10000, was 10001");
  }

  private MultipartUpload.PartReceipt stored(int partNumber, byte[] bytes) {
    var outcome = upload.uploadPart(partNumber, bytes, ContentAddress.sha256Hex(bytes));
    assertThat(outcome).isInstanceOf(MultipartUpload.Stored.class);
    return ((MultipartUpload.Stored) outcome).receipt();
  }

  private static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }
}
