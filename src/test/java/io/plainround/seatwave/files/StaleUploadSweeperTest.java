package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An upload is abandoned only once it is as old as the rule says, never sooner. */
class StaleUploadSweeperTest {

  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
  // The abort rule of lifecycle/seatwave-media.json.
  private static final Duration SEVEN_DAYS = Duration.ofDays(7);
  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  private final StaleUploadSweeper sweeper = new StaleUploadSweeper(SEVEN_DAYS);

  @Test
  void countsAnUploadStartedExactlySevenDaysAgoAsStale() {
    var upload = open("incoming/event-1/a.jpg", NOW.minus(SEVEN_DAYS));

    assertThat(sweeper.stale(List.of(upload), NOW)).containsExactly(upload);
  }

  @Test
  void leavesAnUploadOneSecondYoungerThanTheRuleAlone() {
    var upload = open("incoming/event-1/a.jpg", NOW.minus(SEVEN_DAYS).plus(ONE_SECOND));

    assertThat(sweeper.stale(List.of(upload), NOW)).isEmpty();
  }

  @Test
  void rejectsAMaximumAgeThatIsNotPositive() {
    assertThatThrownBy(() -> new StaleUploadSweeper(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maximum age must be positive, was PT0S");
  }

  private static StaleUploadSweeper.OpenUpload open(String key, Instant initiated) {
    return new StaleUploadSweeper.OpenUpload(key, "upload-1", initiated);
  }
}
