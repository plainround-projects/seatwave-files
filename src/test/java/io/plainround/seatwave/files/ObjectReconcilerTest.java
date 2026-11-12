package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rule "every object the database calls stored exists in the bucket", and the safe clean-up
 * around it.
 */
class ObjectReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  // The longest URL lifetime (15 minutes) plus a margin: the setting the job runs with.
  private static final Duration GRACE = Duration.ofHours(1);
  private static final Instant LONG_AGO = NOW.minus(Duration.ofDays(2));
  private static final Instant JUST_NOW = NOW.minus(Duration.ofMinutes(3));
  private static final String CHECKSUM = "a".repeat(64);
  private static final long SIZE_BYTES = 200_000L;
  private static final String TICKET_KEY = "tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf";

  private final ObjectReconciler reconciler = new ObjectReconciler(GRACE);

  @Test
  void reportsStoredRowWhoseObjectIsMissingFromTheBucket() {
    var row =
        new ObjectReconciler.Row(
            TICKET_KEY, ObjectReconciler.State.STORED, CHECKSUM, SIZE_BYTES, LONG_AGO);

    var report = reconciler.reconcile(List.of(row), List.of(), NOW);

    assertThat(report.missing()).containsExactly(TICKET_KEY);
    assertThat(report.orphansToDelete()).isEmpty();
  }

  @Test
  void reportsStoredRowWhoseBytesDifferInSize() {
    var row =
        new ObjectReconciler.Row(
            TICKET_KEY, ObjectReconciler.State.STORED, CHECKSUM, SIZE_BYTES, LONG_AGO);
    var listed = new ObjectReconciler.Listed(TICKET_KEY, CHECKSUM, SIZE_BYTES - 1, LONG_AGO);

    var report = reconciler.reconcile(List.of(row), List.of(listed), NOW);

    assertThat(report.mismatched()).containsExactly(TICKET_KEY);
    assertThat(report.missing()).isEmpty();
  }

  @Test
  void leavesAnObjectWithoutARowAloneWhileItsUploadMayStillBeConfirmed() {
    var listed = new ObjectReconciler.Listed(TICKET_KEY, CHECKSUM, SIZE_BYTES, JUST_NOW);

    var report = reconciler.reconcile(List.of(), List.of(listed), NOW);

    assertThat(report.orphansToDelete()).isEmpty();
  }

  @Test
  void deletesAnObjectWithoutARowOnceOlderThanTheGracePeriod() {
    var listed = new ObjectReconciler.Listed(TICKET_KEY, CHECKSUM, SIZE_BYTES, LONG_AGO);

    var report = reconciler.reconcile(List.of(), List.of(listed), NOW);

    assertThat(report.orphansToDelete()).containsExactly(TICKET_KEY);
  }

  @Test
  void abandonsAPendingRowOlderThanTheGracePeriod() {
    var row =
        new ObjectReconciler.Row(
            TICKET_KEY, ObjectReconciler.State.PENDING, CHECKSUM, SIZE_BYTES, LONG_AGO);

    var report = reconciler.reconcile(List.of(row), List.of(), NOW);

    assertThat(report.pendingToAbandon()).containsExactly(TICKET_KEY);
    assertThat(report.missing()).isEmpty();
  }

  @Test
  void neverCountsTheObjectOfAStoredRowAsAnOrphan() {
    var row =
        new ObjectReconciler.Row(
            TICKET_KEY, ObjectReconciler.State.STORED, CHECKSUM, SIZE_BYTES, LONG_AGO);
    var listed = new ObjectReconciler.Listed(TICKET_KEY, CHECKSUM, SIZE_BYTES, LONG_AGO);

    var report = reconciler.reconcile(List.of(row), List.of(listed), NOW);

    assertThat(report.orphansToDelete()).isEmpty();
    assertThat(report.missing()).isEmpty();
    assertThat(report.mismatched()).isEmpty();
  }

  @Test
  void finishesADeleteWhoseBytesAreStillInTheBucket() {
    var row =
        new ObjectReconciler.Row(
            TICKET_KEY, ObjectReconciler.State.DELETING, CHECKSUM, SIZE_BYTES, LONG_AGO);
    var listed = new ObjectReconciler.Listed(TICKET_KEY, CHECKSUM, SIZE_BYTES, LONG_AGO);

    var report = reconciler.reconcile(List.of(row), List.of(listed), NOW);

    assertThat(report.deletesToFinish()).containsExactly(TICKET_KEY);
    assertThat(report.orphansToDelete()).isEmpty();
  }

  @Test
  void rejectsAGracePeriodThatIsNotPositive() {
    assertThatThrownBy(() -> new ObjectReconciler(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("grace must be positive, was PT0S");
  }
}
