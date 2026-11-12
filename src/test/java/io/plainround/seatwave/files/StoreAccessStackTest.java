package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Against the running store: no object is readable without a signed URL, a signed URL allows only
 * what was signed, and the store itself refuses a URL after its expiry.
 */
@Tag("stack")
class StoreAccessStackTest {

  private static final String BUCKET = "seatwave-tickets";
  private static final String PDF = "application/pdf";
  private static final int TICKET_BYTES = 200_000;
  private static final long SEED = 2901L;
  private static final Duration UPLOAD_LIFETIME = Duration.ofSeconds(300);
  private static final Duration DOWNLOAD_LIFETIME = Duration.ofSeconds(60);
  private static final Duration SHORT_LIFETIME = Duration.ofSeconds(1);
  // Twice the short lifetime: past the expiry at the store whatever the rounding to seconds.
  private static final Duration PAST_SHORT_LIFETIME = Duration.ofSeconds(2);
  private static final int OK = 200;
  private static final int FORBIDDEN = 403;
  private static final int PRECONDITION_FAILED = 412;

  private static StoreSettings settings;
  private static ObjectStore store;
  private static S3UrlIssuer issuer;
  private static StoreHttp http;
  private static final byte[] TICKET = new byte[TICKET_BYTES];

  @BeforeAll
  static void connect() {
    settings = StoreSettings.load(Path.of("")).discardError().orElseThrow();
    store = new ObjectStore(settings);
    store.createBuckets();
    issuer =
        new S3UrlIssuer(
            settings.endpoint(),
            Region.of(settings.region()),
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())));
    http = new StoreHttp();
    new Random(SEED).nextBytes(TICKET);
  }

  @AfterAll
  static void disconnect() {
    http.close();
    issuer.close();
    store.close();
  }

  @Test
  void refusesAnAnonymousReadOfAStoredTicket() {
    String key = freshKey();
    store.put(BUCKET, key, TICKET, PDF);

    var answer = http.get(settings.endpoint() + "/" + BUCKET + "/" + key);

    assertThat(answer.status()).isEqualTo(FORBIDDEN);
    assertThat(store.isPublic(BUCKET)).isFalse();
  }

  @Test
  void refusesAnAnonymousListingOfEveryBucket() {
    for (String bucket : ObjectStore.BUCKETS) {
      assertThat(http.get(settings.endpoint() + "/" + bucket + "?list-type=2").status())
          .as(bucket)
          .isEqualTo(FORBIDDEN);
    }
  }

  @Test
  void servesTheExactBytesThroughASignedDownloadUrl() {
    String key = freshKey();
    store.put(BUCKET, key, TICKET, PDF);

    var answer = http.get(issuer.downloadUrl(download(key), DOWNLOAD_LIFETIME, "ticket.pdf"));

    assertThat(answer.status()).isEqualTo(OK);
    assertThat(answer.body()).isEqualTo(TICKET);
  }

  @Test
  void refusesASignedDownloadUrlAfterItsExpiry() throws InterruptedException {
    String key = freshKey();
    store.put(BUCKET, key, TICKET, PDF);
    String url = issuer.downloadUrl(download(key), SHORT_LIFETIME, "ticket.pdf");

    // The wait is the input of this test: the store's clock must pass the URL's expiry.
    Thread.sleep(PAST_SHORT_LIFETIME);
    var answer = http.get(url);

    assertThat(answer.status()).isEqualTo(FORBIDDEN);
  }

  @Test
  void refusesASignedUploadWhoseBodyHasAnotherContentType() {
    String key = freshKey();

    var upload = signedUpload(key);
    Map<String, String> otherType = new java.util.HashMap<>(upload.headers());
    otherType.put("Content-Type", "text/html");

    var answer = http.put(upload.url(), TICKET, otherType);

    assertThat(answer.status()).isEqualTo(FORBIDDEN);
    assertThat(store.exists(BUCKET, key)).isFalse();
  }

  @Test
  void refusesASecondSignedUploadToAKeyThatExists() {
    String key = freshKey();
    var upload = signedUpload(key);
    assertThat(http.put(upload.url(), TICKET, upload.headers()).status()).isEqualTo(OK);
    byte[] other = TICKET.clone();
    other[0] ^= 1;

    var answer = http.put(upload.url(), other, upload.headers());

    assertThat(answer.status()).isEqualTo(PRECONDITION_FAILED);
    assertThat(store.get(BUCKET, key)).isEqualTo(TICKET);
  }

  private static S3UrlIssuer.SignedUpload signedUpload(String key) {
    return issuer.signedUpload(
        UrlGrant.upload(BUCKET, key, PDF, TICKET_BYTES, Instant.now().plus(UPLOAD_LIFETIME)),
        UPLOAD_LIFETIME);
  }

  private static UrlGrant download(String key) {
    return UrlGrant.download(BUCKET, key, Instant.now().plus(DOWNLOAD_LIFETIME));
  }

  // A new booking id per test, so the tests never share or overwrite an object.
  private static String freshKey() {
    return "tickets/2901/" + java.util.UUID.randomUUID() + ".pdf";
  }
}
