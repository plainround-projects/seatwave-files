package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Against the running store: a multipart upload with a lost part cannot complete, stays open and
 * invisible, and the sweep aborts it only once it is older than the rule's 7 days.
 */
@Tag("stack")
class StaleUploadStackTest {

  private static final String BUCKET = "seatwave-media";
  private static final int PART_BYTES = (int) MultipartUpload.MIN_PART_BYTES;
  private static final byte FILL = 7;
  private static final Duration SEVEN_DAYS = Duration.ofDays(7);
  private static final Duration EIGHT_DAYS = Duration.ofDays(8);

  private static ObjectStore store;
  private static S3Client client;
  private final StaleUploadSweeper sweeper = new StaleUploadSweeper(SEVEN_DAYS);

  @BeforeAll
  static void connect() {
    store = new ObjectStore(StoreSettings.load(Path.of("")).discardError().orElseThrow());
    store.createBuckets();
    client = store.client();
  }

  @AfterAll
  static void disconnect() {
    store.close();
  }

  @Test
  void refusesToCompleteAnUploadWhosePartTwoNeverArrived() {
    String key = freshKey();
    String uploadId = start(key);
    byte[] part = filled();
    String first = send(key, uploadId, 1, part);

    assertThatThrownBy(() ->
            client.completeMultipartUpload(complete ->
                complete
                    .bucket(BUCKET)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(upload ->
                        upload.parts(completed(1, first, part), completed(2, first, part)))))
        .isInstanceOfSatisfying(
            S3Exception.class,
            refused -> assertThat(refused.awsErrorDetails().errorCode()).isEqualTo("InvalidPart"));
    assertThat(store.exists(BUCKET, key)).isFalse();
    assertThat(store.incompleteUploads(BUCKET)).anyMatch(open -> open.uploadId().equals(uploadId));
  }

  @Test
  void abortsAnUploadOnlyOnceItIsOlderThanTheRule() {
    String key = freshKey();
    String uploadId = start(key);
    send(key, uploadId, 1, filled());
    Instant now = Instant.now();

    var youngSweep = sweeper.abortStale(store, BUCKET, now);
    boolean isOpenAfterYoungSweep = isOpen(uploadId);
    var oldSweep = sweeper.abortStale(store, BUCKET, now.plus(EIGHT_DAYS));

    assertThat(youngSweep).noneMatch(open -> open.uploadId().equals(uploadId));
    assertThat(isOpenAfterYoungSweep).isTrue();
    assertThat(oldSweep).anyMatch(open -> open.uploadId().equals(uploadId));
    assertThat(isOpen(uploadId)).isFalse();
  }

  private static boolean isOpen(String uploadId) {
    return store.incompleteUploads(BUCKET).stream()
        .anyMatch(open -> open.uploadId().equals(uploadId));
  }

  private static String start(String key) {
    return client
        .createMultipartUpload(
            create -> create.bucket(BUCKET).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256))
        .uploadId();
  }

  private static String send(String key, String uploadId, int partNumber, byte[] bytes) {
    return client
        .uploadPart(
            upload ->
                upload
                    .bucket(BUCKET)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .checksumSHA256(sha256(bytes)),
            RequestBody.fromBytes(bytes))
        .eTag();
  }

  private static CompletedPart completed(int partNumber, String eTag, byte[] bytes) {
    return CompletedPart.builder()
        .partNumber(partNumber)
        .eTag(eTag)
        .checksumSHA256(sha256(bytes))
        .build();
  }

  private static String sha256(byte[] bytes) {
    return Base64.getEncoder().encodeToString(ContentAddress.sha256(bytes));
  }

  private static byte[] filled() {
    byte[] bytes = new byte[PART_BYTES];
    Arrays.fill(bytes, FILL);
    return bytes;
  }

  private static String freshKey() {
    return "incoming/event-2903/" + UUID.randomUUID() + ".jpg";
  }
}
