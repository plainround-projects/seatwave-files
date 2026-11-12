package io.plainround.seatwave.files;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Command 3 of Section 8 and Lab 1, step 5: a ticket uploaded and downloaded through real Signature
 * Version 4 URLs from {@link S3UrlIssuer}, and the requests the store must refuse: a second upload
 * to the same key, a body of another type, no signature at all, and a link used after its expiry.
 */
public final class TicketLinksCli {

  // The ticket of Section 8, Command 3; the same bytes on every run (IntegrityDemoCli's seed).
  private static final String TICKET_KEY = "tickets/1/019a7302-8e11-7b5c-a4d2-6e9f0c3b7a15.pdf";
  private static final String BUCKET = "seatwave-tickets";
  private static final int TICKET_BYTES = 200_000;
  private static final long TICKET_SEED = 18L;
  private static final String PDF = "application/pdf";
  // The lifetimes of the module: 300 s for an upload, 60 s for a ticket link.
  private static final Duration UPLOAD_LIFETIME = Duration.ofSeconds(300);
  private static final Duration DOWNLOAD_LIFETIME = Duration.ofSeconds(60);
  // A link that lives 1 s, used 2 s later: the store, not SeatWave, must refuse it.
  private static final Duration SHORT_LIFETIME = Duration.ofSeconds(1);
  private static final Duration WAIT_PAST_EXPIRY = Duration.ofSeconds(2);

  private TicketLinksCli() {}

  /** Runs the seven requests and prints one line each. */
  public static void main(String[] arguments) throws InterruptedException {
    StoreSettings settings = LabSettings.loadOrExit(Path.of(""));
    byte[] ticket = new byte[TICKET_BYTES];
    new Random(TICKET_SEED).nextBytes(ticket);
    try (ObjectStore store = new ObjectStore(settings);
        S3UrlIssuer issuer = issuer(settings);
        StoreHttp http = new StoreHttp()) {
      // The program's own key, removed first so that every run starts from "no ticket yet".
      store.delete(BUCKET, TICKET_KEY);
      Instant now = Instant.now();
      UrlGrant upload =
          UrlGrant.upload(BUCKET, TICKET_KEY, PDF, TICKET_BYTES, now.plus(UPLOAD_LIFETIME));
      S3UrlIssuer.SignedUpload signed = issuer.signedUpload(upload, UPLOAD_LIFETIME);
      String uploadUrl = signed.url();
      Map<String, String> otherType = new HashMap<>(signed.headers());
      otherType.put("Content-Type", "text/html");
      Map<String, String> signedHeaders = signed.headers();
      System.out.println(
          String.format(
              Locale.ROOT,
              "ticket: %,d bytes, SHA-256 %s",
              TICKET_BYTES,
              ContentAddress.sha256Hex(ticket)));
      report(
          "PUT through the signed upload URL",
          http.put(uploadUrl, ticket, signedHeaders).summary());
      report(
          "the same PUT again (If-None-Match: *)",
          http.put(uploadUrl, ticket, signedHeaders).summary());
      report(
          "a PUT that sends text/html instead", http.put(uploadUrl, ticket, otherType).summary());
      UrlGrant download = UrlGrant.download(BUCKET, TICKET_KEY, now.plus(DOWNLOAD_LIFETIME));
      StoreHttp.Answer fetched =
          http.get(issuer.downloadUrl(download, DOWNLOAD_LIFETIME, "ticket.pdf"));
      report(
          "GET through the signed 60 s ticket URL",
          fetched.summary()
              + ", bytes match: "
              + ContentAddress.sha256Hex(fetched.body()).equals(ContentAddress.sha256Hex(ticket)));
      report(
          "anonymous GET of the same key",
          http.get(settings.endpoint() + "/" + BUCKET + "/" + TICKET_KEY).summary());
      String shortUrl = issuer.downloadUrl(download, SHORT_LIFETIME, "ticket.pdf");
      report("GET through a 1 s URL at once", http.get(shortUrl).summary());
      // The wait is the input of this request: the URL must be past its expiry at the store.
      Thread.sleep(WAIT_PAST_EXPIRY);
      report("the same 1 s URL, 2 s later", http.get(shortUrl).summary());
    }
  }

  private static S3UrlIssuer issuer(StoreSettings settings) {
    return new S3UrlIssuer(
        settings.endpoint(),
        Region.of(settings.region()),
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())));
  }

  private static void report(String request, String answer) {
    System.out.println(String.format(Locale.ROOT, "  %-40s %s", request, answer));
  }
}
