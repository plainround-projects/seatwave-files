package io.plainround.seatwave.files;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * The production form of {@link PresignedUrlSigner}: real AWS Signature Version 4 URLs from the AWS
 * SDK for Java v2, for the lab's RustFS store or for S3 in production. The stack tests sign URLs
 * with it and present them to the real store.
 *
 * <p>Thread-safety: an {@link S3Presigner} is thread-safe and long-lived; create one, share it,
 * close it at shutdown, never per request.
 */
public final class S3UrlIssuer implements AutoCloseable {

  private final S3Presigner presigner;

  /**
   * @param endpoint {@code http://127.0.0.1:22900} for the lab's store; omit the override for S3
   * @param region the signing region; the lab's store accepts {@code us-east-1}
   * @param credentials read from the secret store at start-up, never from code or files in the
   *     repository
   */
  public S3UrlIssuer(URI endpoint, Region region, AwsCredentialsProvider credentials) {
    this.presigner =
        S3Presigner.builder()
            .endpointOverride(Objects.requireNonNull(endpoint, "endpoint"))
            .region(Objects.requireNonNull(region, "region"))
            .credentialsProvider(Objects.requireNonNull(credentials, "credentials"))
            // The lab's store, on one host name, serves buckets as paths, not as sub-domains.
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
  }

  /**
   * A PUT URL whose signature covers the content type and length, so S3 refuses any other body with
   * 403 SignatureDoesNotMatch; and If-None-Match: *, so the upload cannot replace an object. The
   * client must send exactly the signed headers: {@code Content-Type}, {@code Content-Length} and
   * {@code If-None-Match: *}.
   */
  public String uploadUrl(UrlGrant grant, Duration lifetime) {
    return signedUpload(grant, lifetime).url();
  }

  /**
   * The upload URL with the headers its signature covers, as the browser must send them (the JDK
   * client sets {@code Host} and {@code Content-Length} itself from the URL and the body).
   *
   * @param url the pre-signed URL
   * @param headers every signed header and its value, as the SDK signed it
   */
  public record SignedUpload(String url, Map<String, String> headers) {
    /** Copies the headers. */
    public SignedUpload {
      Objects.requireNonNull(url, "url");
      headers = Map.copyOf(headers);
    }
  }

  /** {@link #uploadUrl}, together with the headers the client must send. */
  public SignedUpload signedUpload(UrlGrant grant, Duration lifetime) {
    PresignedPutObjectRequest signed =
        presigner.presignPutObject(presign ->
            presign
                .signatureDuration(lifetime)
                .putObjectRequest(put ->
                    put.bucket(grant.bucket())
                        .key(grant.objectKey())
                        .contentType(grant.contentType())
                        .contentLength(grant.contentLengthBytes())
                        .ifNoneMatch("*")));
    Map<String, String> headers = new TreeMap<>();
    signed.signedHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
    return new SignedUpload(signed.url().toString(), headers);
  }

  /** A GET URL for one object, answered with the ticket's own name for the fan's browser. */
  public String downloadUrl(UrlGrant grant, Duration lifetime, String fileName) {
    return presigner
        .presignGetObject(presign ->
            presign
                .signatureDuration(lifetime)
                .getObjectRequest(get ->
                    get.bucket(grant.bucket())
                        .key(grant.objectKey())
                        .responseContentDisposition("attachment; filename=\"" + fileName + "\"")))
        .url()
        .toString();
  }

  @Override
  public void close() {
    presigner.close();
  }
}
