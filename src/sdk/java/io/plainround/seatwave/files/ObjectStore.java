package io.plainround.seatwave.files;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleAndOperator;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;

/**
 * SeatWave's side of the object store, over the S3 API with the AWS SDK for Java v2: the four
 * buckets and their lifecycle rules, uploads with a SHA-256 checksum, a listing with checksums for
 * the reconciliation job, deletes, and the incomplete multipart uploads.
 *
 * <p>Thread-safety: an {@link S3Client} is thread-safe and long-lived; create one store per
 * process, share it, close it at shutdown.
 */
public final class ObjectStore implements AutoCloseable {

  /** The four buckets of Module 29; later modules use the same names. */
  public static final List<String> BUCKETS =
      List.of("seatwave-tickets", "seatwave-media", "seatwave-static", "seatwave-internal");

  /** Rule ids of {@code lifecycle/seatwave-media.json} that the lab's store applies. */
  public static final String DRAFTS_RULE = "incoming-uploads-expire-after-7-days";

  public static final String ABORT_RULE = "abort-incomplete-multipart-after-7-days";

  /** The one rule of {@code seatwave-media.json} the lab's store does not take. */
  public static final String TRANSITION_RULE = "posters-to-infrequent-access-after-90-days";

  /** Lab 2's ticket rule: expire a ticket once its event is closed (the tag is the decision). */
  public static final String CLOSED_EVENT_RULE = "tickets-expire-after-event-closed";

  public static final String RETENTION_TAG = "retention";
  public static final String EVENT_CLOSED = "event-closed";

  // From seatwave-media.json: drafts and abandoned uploads both go after 7 days.
  private static final int DRAFT_DAYS = 7;
  // The smallest Days value S3 accepts; the tag, not the age, carries the decision.
  private static final int CLOSED_EVENT_DAYS = 1;
  private static final int HTTP_NOT_FOUND = 404;

  private final S3Client client;

  /** One client for the process, path-style because the lab serves every bucket on one host. */
  public ObjectStore(StoreSettings settings) {
    Objects.requireNonNull(settings, "settings");
    this.client =
        S3Client.builder()
            .endpointOverride(settings.endpoint())
            .region(Region.of(settings.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
            .forcePathStyle(true)
            // With the SDK's default aws-chunked bodies, RustFS 1.0.5 answered every checksummed
            // PUT with 400 BadDigest (found 12 November 2026); a plain body with the checksum in a
            // header is accepted, and the checksum is still verified (a damaged part is refused).
            .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
            // The program states the checksum it wants (SHA-256, computed before sending) instead
            // of letting the SDK add one of its own choosing to every request.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
  }

  /** Creates each bucket that does not exist yet; a new bucket is private, with no policy. */
  public void createBuckets() {
    for (String bucket : BUCKETS) {
      try {
        client.createBucket(create -> create.bucket(bucket));
      } catch (BucketAlreadyOwnedByYouException alreadyThere) {
        // A second run of the setup finds the buckets of the first: nothing to do.
      }
    }
  }

  /**
   * Applies the rules of {@code lifecycle/seatwave-media.json} that need no storage classes (drafts
   * expire, abandoned multipart uploads are aborted, both after 7 days) and Lab 2's ticket rule.
   */
  public void applyLifecycleRules() {
    client.putBucketLifecycleConfiguration(put ->
        put.bucket("seatwave-media")
            .lifecycleConfiguration(
                BucketLifecycleConfiguration.builder()
                    .rules(
                        rule(DRAFTS_RULE, LifecycleRuleFilter.builder().prefix("incoming/"))
                            .expiration(LifecycleExpiration.builder().days(DRAFT_DAYS).build())
                            .build(),
                        rule(ABORT_RULE, LifecycleRuleFilter.builder().prefix(""))
                            .abortIncompleteMultipartUpload(
                                AbortIncompleteMultipartUpload.builder()
                                    .daysAfterInitiation(DRAFT_DAYS)
                                    .build())
                            .build())
                    .build()));
    LifecycleRuleFilter.Builder closedTickets =
        LifecycleRuleFilter.builder()
            .and(
                LifecycleRuleAndOperator.builder()
                    .prefix("tickets/")
                    .tags(Tag.builder().key(RETENTION_TAG).value(EVENT_CLOSED).build())
                    .build());
    client.putBucketLifecycleConfiguration(put ->
        put.bucket("seatwave-tickets")
            .lifecycleConfiguration(
                BucketLifecycleConfiguration.builder()
                    .rules(
                        rule(CLOSED_EVENT_RULE, closedTickets)
                            .expiration(
                                LifecycleExpiration.builder().days(CLOSED_EVENT_DAYS).build())
                            .build())
                    .build()));
  }

  /** The rule ids the store holds for one bucket, as it reports them. */
  public List<String> lifecycleRuleIds(String bucket) {
    return client.getBucketLifecycleConfiguration(get -> get.bucket(bucket)).rules().stream()
        .map(LifecycleRule::id)
        .toList();
  }

  /** Whether the store reports the bucket as public; a bucket without a policy is not. */
  public boolean isPublic(String bucket) {
    try {
      return client.getBucketPolicyStatus(get -> get.bucket(bucket)).policyStatus().isPublic();
    } catch (S3Exception noPolicy) {
      // S3 answers NoSuchBucketPolicy for a bucket without a policy: nothing makes it public.
      if ("NoSuchBucketPolicy".equals(noPolicy.awsErrorDetails().errorCode())) {
        return false;
      }
      throw noPolicy;
    }
  }

  /** Stores bytes with a SHA-256 checksum the store verifies on arrival, and optional tags. */
  public void put(String bucket, String key, byte[] bytes, String contentType) {
    String checksum = Base64.getEncoder().encodeToString(ContentAddress.sha256(bytes));
    client.putObject(
        put ->
            put.bucket(bucket)
                .key(key)
                .contentType(contentType)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .checksumSHA256(checksum),
        RequestBody.fromBytes(bytes));
  }

  /** Reads an object's bytes. */
  public byte[] get(String bucket, String key) {
    return client.getObjectAsBytes(get -> get.bucket(bucket).key(key)).asByteArray();
  }

  /** Whether an object exists under the key. */
  public boolean exists(String bucket, String key) {
    try {
      client.headObject(head -> head.bucket(bucket).key(key));
      return true;
    } catch (NoSuchKeyException missing) {
      return false;
    } catch (S3Exception notFound) {
      // A HEAD has no body, so a missing key arrives as a bare 404 rather than NoSuchKey.
      if (notFound.statusCode() == HTTP_NOT_FOUND) {
        return false;
      }
      throw notFound;
    }
  }

  /** Deletes one object; deleting a key that is already gone succeeds, as in S3. */
  public void delete(String bucket, String key) {
    client.deleteObject(delete -> delete.bucket(bucket).key(key));
  }

  /**
   * Every object of a bucket with its size, time and SHA-256 (from a HEAD with checksums on). At
   * SeatWave's scale this is the daily inventory report instead; the lab's buckets are small.
   */
  public List<ObjectReconciler.Listed> listing(String bucket) {
    List<ObjectReconciler.Listed> listed = new ArrayList<>();
    for (S3Object object : client.listObjectsV2Paginator(list -> list.bucket(bucket)).contents()) {
      HeadObjectResponse head =
          client.headObject(
              read -> read.bucket(bucket).key(object.key()).checksumMode(ChecksumMode.ENABLED));
      listed.add(
          new ObjectReconciler.Listed(
              object.key(), hex(head.checksumSHA256()), object.size(), object.lastModified()));
    }
    return listed;
  }

  /** The multipart uploads of a bucket that were started and neither completed nor aborted. */
  public List<StaleUploadSweeper.OpenUpload> incompleteUploads(String bucket) {
    List<StaleUploadSweeper.OpenUpload> open = new ArrayList<>();
    for (MultipartUpload upload :
        client.listMultipartUploadsPaginator(list -> list.bucket(bucket)).uploads()) {
      open.add(
          new StaleUploadSweeper.OpenUpload(upload.key(), upload.uploadId(), upload.initiated()));
    }
    return open;
  }

  /** Aborts one multipart upload, which discards its parts. */
  public void abort(String bucket, StaleUploadSweeper.OpenUpload upload) {
    client.abortMultipartUpload(
        abort -> abort.bucket(bucket).key(upload.objectKey()).uploadId(upload.uploadId()));
  }

  /** The SDK client, for the multipart program and the tests that drive the S3 API directly. */
  public S3Client client() {
    return client;
  }

  /** When the store says an object was last written, for tests that age it on purpose. */
  public Instant lastModified(String bucket, String key) {
    return client.headObject(head -> head.bucket(bucket).key(key)).lastModified();
  }

  @Override
  public void close() {
    client.close();
  }

  private static LifecycleRule.Builder rule(String id, LifecycleRuleFilter.Builder filter) {
    return LifecycleRule.builder().id(id).status(ExpirationStatus.ENABLED).filter(filter.build());
  }

  // S3 reports checksums in base64; the table and ContentAddress use 64 lowercase hex digits.
  private static String hex(String base64Checksum) {
    if (base64Checksum == null || base64Checksum.isEmpty()) {
      return "";
    }
    return HexFormat.of().formatHex(Base64.getDecoder().decode(base64Checksum));
  }
}
