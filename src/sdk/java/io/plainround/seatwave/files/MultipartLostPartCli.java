package io.plainround.seatwave.files;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Section 8, Command 4, and Lab 2, step 5: {@link MultipartDemoCli}'s 20,000,000-byte poster sent
 * to the real store in 5 MiB parts. One upload has a part damaged on the way (refused, sent again,
 * completed); a second loses part 2, so its completion is refused and it stays open until the sweep
 * for stale uploads aborts it.
 */
public final class MultipartLostPartCli {

  private static final String BUCKET = "seatwave-media";
  private static final String COMPLETED_KEY =
      "incoming/event-1/019a7310-0b7d-7e22-8c4a-5d1f9b6e2c03.jpg";
  private static final String ABANDONED_KEY =
      "incoming/event-1/019a7311-4f20-7a91-b3c6-0e8d2a7f5b19.jpg";
  // MultipartDemoCli's poster: the same bytes and the same parts.
  private static final int POSTER_BYTES = 20_000_000;
  private static final long POSTER_SEED = 29L;
  private static final int PART_BYTES = (int) MultipartUpload.MIN_PART_BYTES;
  private static final int DAMAGED_PART = 3;
  private static final int LOST_PART = 2;
  private static final int DAMAGED_BYTE = 1_000_000;
  // The abort rule of lifecycle/seatwave-media.json: 7 days.
  private static final Duration ABORT_AFTER = Duration.ofDays(7);
  private static final Duration EIGHT_DAYS = Duration.ofDays(8);

  private MultipartLostPartCli() {}

  /** Runs both uploads and the sweep, and prints what the store answered at each step. */
  public static void main(String[] arguments) {
    StoreSettings settings = LabSettings.loadOrExit(Path.of(""));
    byte[] poster = new byte[POSTER_BYTES];
    new Random(POSTER_SEED).nextBytes(poster);
    List<byte[]> parts = split(poster);
    try (ObjectStore store = new ObjectStore(settings)) {
      S3Client client = store.client();
      store.delete(BUCKET, COMPLETED_KEY);
      new StaleUploadSweeper(Duration.ofSeconds(1))
          .abortStale(store, BUCKET, Instant.now().plus(EIGHT_DAYS));
      System.out.println(
          String.format(
              Locale.ROOT,
              "poster: %,d bytes in %d parts, SHA-256 %s",
              poster.length,
              parts.size(),
              ContentAddress.sha256Hex(poster)));

      String uploadId = start(client, COMPLETED_KEY);
      List<CompletedPart> completed = new ArrayList<>();
      for (int index = 0; index < parts.size(); index++) {
        int partNumber = index + 1;
        if (partNumber == DAMAGED_PART) {
          byte[] damaged = parts.get(index).clone();
          damaged[DAMAGED_BYTE] ^= 0b0000_0100;
          System.out.println(
              "  part 3 with one bit flipped, declared checksum of the original: "
                  + send(
                          client,
                          new PartTarget(COMPLETED_KEY, uploadId, partNumber),
                          damaged,
                          parts.get(index))
                      .summary());
        }
        Sent sent =
            send(
                client,
                new PartTarget(COMPLETED_KEY, uploadId, partNumber),
                parts.get(index),
                parts.get(index));
        System.out.println("  part " + partNumber + ": " + sent.summary());
        completed.add(part(partNumber, sent.eTag(), parts.get(index)));
      }
      String composite =
          client
              .completeMultipartUpload(complete ->
                  complete
                      .bucket(BUCKET)
                      .key(COMPLETED_KEY)
                      .uploadId(uploadId)
                      .multipartUpload(upload -> upload.parts(completed)))
              .checksumSHA256();
      byte[] readBack = store.get(BUCKET, COMPLETED_KEY);
      System.out.println(
          "completed: composite checksum "
              + composite
              + "; read back equals the poster: "
              + Arrays.equals(readBack, poster));

      String lostId = start(client, ABANDONED_KEY);
      List<CompletedPart> listed = new ArrayList<>();
      for (int index = 0; index < parts.size(); index++) {
        int partNumber = index + 1;
        // The client believes part 2 went out, and lists it with the ETag S3 gives a part: its MD5.
        String eTag =
            partNumber == LOST_PART
                ? md5ETag(parts.get(index))
                : send(
                        client,
                        new PartTarget(ABANDONED_KEY, lostId, partNumber),
                        parts.get(index),
                        parts.get(index))
                    .eTag();
        listed.add(part(partNumber, eTag, parts.get(index)));
      }
      System.out.println("second upload: parts 1, 3, 4 arrived, part 2 was lost on the way");
      try {
        client.completeMultipartUpload(complete ->
            complete
                .bucket(BUCKET)
                .key(ABANDONED_KEY)
                .uploadId(lostId)
                .multipartUpload(upload -> upload.parts(listed)));
        System.out.println(
            "  complete listing parts 1 to 4: COMPLETED (the lost part was not noticed)");
      } catch (S3Exception refused) {
        System.out.println(
            "  complete listing parts 1 to 4: "
                + refused.statusCode()
                + " "
                + refused.awsErrorDetails().errorCode());
      }
      System.out.println(
          "  object under its key: "
              + store.exists(BUCKET, ABANDONED_KEY)
              + "; incomplete uploads in the bucket: "
              + store.incompleteUploads(BUCKET).size());
      StaleUploadSweeper sweeper = new StaleUploadSweeper(ABORT_AFTER);
      Instant now = Instant.now();
      System.out.println(
          "sweep now (uploads under 7 days old are left alone): "
              + sweeper.findStale(store, BUCKET, now).size()
              + " stale");
      System.out.println(
          "sweep as of 8 days later: aborted "
              + sweeper.abortStale(store, BUCKET, now.plus(EIGHT_DAYS)).size()
              + "; incomplete uploads left: "
              + store.incompleteUploads(BUCKET).size());
      store.delete(BUCKET, COMPLETED_KEY);
    }
  }

  private static String start(S3Client client, String key) {
    return client
        .createMultipartUpload(create ->
            create
                .bucket(BUCKET)
                .key(key)
                .contentType("image/jpeg")
                .checksumAlgorithm(ChecksumAlgorithm.SHA256))
        .uploadId();
  }

  // The client hashes the intended bytes before they leave; the store hashes what arrives.
  private static Sent send(S3Client client, PartTarget target, byte[] onTheWire, byte[] intended) {
    try {
      String eTag =
          client
              .uploadPart(
                  upload ->
                      upload
                          .bucket(BUCKET)
                          .key(target.objectKey())
                          .uploadId(target.uploadId())
                          .partNumber(target.partNumber())
                          .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                          .checksumSHA256(base64Sha256(intended)),
                  RequestBody.fromBytes(onTheWire))
              .eTag();
      return new Sent("stored", eTag);
    } catch (S3Exception refused) {
      return new Sent(
          "REFUSED, " + refused.statusCode() + " " + refused.awsErrorDetails().errorCode(), "");
    }
  }

  /** Which part of which upload a request carries. */
  private record PartTarget(String objectKey, String uploadId, int partNumber) {}

  /** What the store said about one part, and the ETag to list it with. */
  private record Sent(String summary, String eTag) {}

  private static CompletedPart part(int partNumber, String eTag, byte[] bytes) {
    return CompletedPart.builder()
        .partNumber(partNumber)
        .eTag(eTag)
        .checksumSHA256(base64Sha256(bytes))
        .build();
  }

  private static String md5ETag(byte[] bytes) {
    try {
      return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)) + "\"";
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      // Every JDK must provide MD5, so this is a broken runtime, not bad input.
      throw new IllegalStateException("MD5 unavailable", missingAlgorithm);
    }
  }

  private static String base64Sha256(byte[] bytes) {
    return Base64.getEncoder().encodeToString(ContentAddress.sha256(bytes));
  }

  private static List<byte[]> split(byte[] whole) {
    List<byte[]> parts = new ArrayList<>();
    for (int offset = 0; offset < whole.length; offset += PART_BYTES) {
      parts.add(Arrays.copyOfRange(whole, offset, Math.min(offset + PART_BYTES, whole.length)));
    }
    return parts;
  }
}
