package io.plainround.seatwave.files;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Signs an upload URL for an organizer's poster and a download URL for a fan's ticket, then
 * presents nine requests to the storage side: the honest ones, and the ways a URL goes wrong.
 * Prints one line per request. The key is random on every run and the signatures are never printed,
 * because a signature is the bearer's whole permission.
 */
public final class PresignedUrlDemoCli {

  // The day this module was written, 10:00 UTC; every request below is an offset from it.
  private static final Instant SIGNED_AT = Instant.parse("2026-11-11T10:00:00Z");
  // Uploads get 5 minutes: a 4.2 MB poster at a slow 2 Mbps uplink needs about 17 s.
  private static final Duration UPLOAD_LIFETIME = Duration.ofMinutes(5);
  // A ticket link is used at once, by a redirect, so 60 s is plenty.
  private static final Duration DOWNLOAD_LIFETIME = Duration.ofSeconds(60);
  // The longest lifetime this signer grants, from configuration; S3 itself allows up to 7 days.
  private static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
  private static final int KEY_BYTES = 32;
  private static final String ENDPOINT = "https://files.seatwave.example";
  private static final long POSTER_BYTES = 4_200_000L;
  private static final String POSTER_KEY =
      "incoming/event-1/019a7300-5c2e-7d41-9a3b-2f6c8e1d4b70.jpg";
  private static final String TICKET_KEY = "tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf";
  private static final String SIGNATURE_MARK = "X-Sw-Signature=";

  private PresignedUrlDemoCli() {}

  /** Runs the nine requests and prints their verdicts. */
  public static void main(String[] arguments) {
    byte[] signingKey = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(signingKey);
    PresignedUrlSigner signer =
        new PresignedUrlSigner(
            signingKey, Clock.fixed(SIGNED_AT, ZoneOffset.UTC), MAX_LIFETIME, ENDPOINT);

    String uploadUrl =
        signer.sign(
            UrlGrant.upload(
                "seatwave-media",
                POSTER_KEY,
                "image/jpeg",
                POSTER_BYTES,
                SIGNED_AT.plus(UPLOAD_LIFETIME)));
    String ticketUrl =
        signer.sign(
            UrlGrant.download("seatwave-tickets", TICKET_KEY, SIGNED_AT.plus(DOWNLOAD_LIFETIME)));
    System.out.println("signed at " + SIGNED_AT + ", signatures redacted");
    System.out.println("upload URL:   " + redact(uploadUrl));
    System.out.println("download URL: " + redact(ticketUrl));
    System.out.println();
    System.out.println(
        String.format(Locale.ROOT, "%-3s %-46s %-7s %s", "#", "request", "at", "verdict"));

    present(
        signer,
        1,
        "organizer PUTs the poster as signed",
        12,
        new PresignedUrlSigner.Request(UrlGrant.Method.PUT, uploadUrl, "image/jpeg", POSTER_BYTES));
    present(
        signer,
        2,
        "the same PUT, after the 300 s lifetime",
        301,
        new PresignedUrlSigner.Request(UrlGrant.Method.PUT, uploadUrl, "image/jpeg", POSTER_BYTES));
    present(
        signer,
        3,
        "path edited to another event's prefix",
        12,
        new PresignedUrlSigner.Request(
            UrlGrant.Method.PUT,
            uploadUrl.replace("incoming/event-1/", "incoming/event-2/"),
            "image/jpeg",
            POSTER_BYTES));
    present(
        signer,
        4,
        "expiry edited one day later",
        400,
        new PresignedUrlSigner.Request(
            UrlGrant.Method.PUT,
            replaceExpiry(
                uploadUrl,
                expires -> Long.toString(Long.parseLong(expires) + Duration.ofDays(1).toSeconds())),
            "image/jpeg",
            POSTER_BYTES));
    present(
        signer,
        5,
        "a 20,000,000-byte body on the 4.2 MB grant",
        12,
        new PresignedUrlSigner.Request(
            UrlGrant.Method.PUT, uploadUrl, "image/jpeg", UrlGrant.MAX_SINGLE_UPLOAD_BYTES));
    present(
        signer,
        6,
        "an HTML body on the JPEG grant",
        12,
        new PresignedUrlSigner.Request(UrlGrant.Method.PUT, uploadUrl, "text/html", POSTER_BYTES));
    present(
        signer,
        7,
        "fan GETs the ticket through the redirect",
        1,
        new PresignedUrlSigner.Request(UrlGrant.Method.GET, ticketUrl, "", 0));
    present(
        signer,
        8,
        "someone else GETs the same ticket URL",
        40,
        new PresignedUrlSigner.Request(UrlGrant.Method.GET, ticketUrl, "", 0));
    present(
        signer,
        9,
        "the ticket URL used to PUT a file",
        5,
        new PresignedUrlSigner.Request(
            UrlGrant.Method.PUT, ticketUrl, "application/pdf", POSTER_BYTES));

    System.out.println();
    try {
      signer.sign(
          UrlGrant.download("seatwave-tickets", TICKET_KEY, SIGNED_AT.plus(Duration.ofDays(8))));
      System.out.println("an 8-day ticket URL was signed");
    } catch (IllegalArgumentException refused) {
      System.out.println("asking for an 8-day ticket URL: refused, " + refused.getMessage());
    }
  }

  private static void present(
      PresignedUrlSigner signer,
      int number,
      String label,
      long secondsAfterSigning,
      PresignedUrlSigner.Request request) {
    Instant at = SIGNED_AT.plusSeconds(secondsAfterSigning);
    UrlVerdict verdict = signer.withClock(Clock.fixed(at, ZoneOffset.UTC)).verify(request);
    String outcome =
        switch (verdict) {
          case UrlVerdict.Accepted accepted ->
              "ACCEPTED " + accepted.grant().method() + " " + accepted.grant().objectKey();
          case UrlVerdict.Refused refused -> refused.reason() + ": " + refused.detail();
        };
    System.out.println(
        String.format(
            Locale.ROOT,
            "%-3d %-46s %-7s %s",
            number,
            label,
            "+" + secondsAfterSigning + " s",
            outcome));
  }

  // Replaces the value of X-Sw-Expires, the way an attacker would edit the query string by hand.
  private static String replaceExpiry(String url, UnaryOperator<String> change) {
    String mark = "X-Sw-Expires=";
    int start = url.indexOf(mark) + mark.length();
    int end = url.indexOf('&', start);
    return url.substring(0, start) + change.apply(url.substring(start, end)) + url.substring(end);
  }

  // Never print a working signature: anyone who copies a log line would hold the permission.
  private static String redact(String url) {
    int start = url.indexOf(SIGNATURE_MARK) + SIGNATURE_MARK.length();
    return url.substring(0, start) + "<" + (url.length() - start) + " hex digits, redacted>";
  }
}
