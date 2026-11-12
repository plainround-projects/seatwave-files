package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** A pre-signed URL allows exactly what was signed, only until its expiry, and nothing else. */
class PresignedUrlSignerTest {

  private static final Instant SIGNED_AT = Instant.parse("2026-11-11T10:00:00Z");
  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
  // 256 bits, the signer's minimum.
  private static final int KEY_BYTES = 32;
  // A fixed test key; production keys come from the secret store, never from code.
  private static final byte[] TEST_KEY = filled(KEY_BYTES);
  private static final String ENDPOINT = "https://files.seatwave.example";
  private static final String KEY = "incoming/event-1/019a7300-5c2e-7d41-9a3b-2f6c8e1d4b70.jpg";
  private static final long BYTES = 4_200_000L;

  private final PresignedUrlSigner signer =
      new PresignedUrlSigner(
          TEST_KEY, Clock.fixed(SIGNED_AT, ZoneOffset.UTC), MAX_LIFETIME, ENDPOINT);
  private final String uploadUrl =
      signer.sign(
          UrlGrant.upload("seatwave-media", KEY, "image/jpeg", BYTES, SIGNED_AT.plus(LIFETIME)));

  @Test
  void acceptsTheSignedUploadBeforeItExpires() {
    var verdict = at(SIGNED_AT.plusSeconds(1)).verify(put(uploadUrl, "image/jpeg", BYTES));

    assertThat(verdict).isInstanceOf(UrlVerdict.Accepted.class);
  }

  @Test
  void refusesTheSignedUploadFromTheInstantItExpires() {
    var verdict = at(SIGNED_AT.plus(LIFETIME)).verify(put(uploadUrl, "image/jpeg", BYTES));

    assertThat(verdict)
        .isEqualTo(new UrlVerdict.Refused(UrlVerdict.Reason.EXPIRED, "expired 0 s ago"));
  }

  @Test
  void refusesAnEditedObjectKeyAsAForgery() {
    var edited = uploadUrl.replace("event-1", "event-2");

    var verdict = at(SIGNED_AT.plusSeconds(1)).verify(put(edited, "image/jpeg", BYTES));

    assertThat(verdict)
        .isInstanceOfSatisfying(
            UrlVerdict.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(UrlVerdict.Reason.BAD_SIGNATURE));
  }

  @Test
  void refusesAnExtendedExpiryAsAForgeryEvenAfterTheRealExpiry() {
    long signedExpiry = SIGNED_AT.plus(LIFETIME).getEpochSecond();
    var edited =
        uploadUrl.replace(
            "X-Sw-Expires=" + signedExpiry,
            "X-Sw-Expires=" + (signedExpiry + Duration.ofDays(1).toSeconds()));

    var verdict = at(SIGNED_AT.plus(Duration.ofHours(1))).verify(put(edited, "image/jpeg", BYTES));

    assertThat(verdict)
        .isInstanceOfSatisfying(
            UrlVerdict.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(UrlVerdict.Reason.BAD_SIGNATURE));
  }

  @Test
  void refusesABodyLongerThanTheSignedLength() {
    var verdict = at(SIGNED_AT.plusSeconds(1)).verify(put(uploadUrl, "image/jpeg", BYTES + 1));

    assertThat(verdict)
        .isInstanceOfSatisfying(
            UrlVerdict.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(UrlVerdict.Reason.CONTENT_MISMATCH));
  }

  @Test
  void refusesToSignALifetimeAboveTheConfiguredMaximum() {
    var tooLong =
        UrlGrant.download(
            "seatwave-tickets", "tickets/1/a.pdf", SIGNED_AT.plus(Duration.ofDays(7)));

    assertThatThrownBy(() -> signer.sign(tooLong)).isInstanceOf(IllegalArgumentException.class);
  }

  private PresignedUrlSigner at(Instant now) {
    return signer.withClock(Clock.fixed(now, ZoneOffset.UTC));
  }

  private static PresignedUrlSigner.Request put(
      String url, String contentType, long contentLengthBytes) {
    return new PresignedUrlSigner.Request(
        UrlGrant.Method.PUT, url, contentType, contentLengthBytes);
  }

  private static byte[] filled(int length) {
    byte[] key = new byte[length];
    Arrays.fill(key, (byte) 0x5a);
    return key;
  }
}
