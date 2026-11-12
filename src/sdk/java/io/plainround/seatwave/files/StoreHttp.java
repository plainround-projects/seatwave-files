package io.plainround.seatwave.files;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain HTTP to the store, the way a browser uses a pre-signed URL or an anonymous visitor tries a
 * key: no SDK, no credentials, only the URL and the headers. Reports the status and S3's error
 * code.
 *
 * <p>Thread-safety: the JDK's {@link HttpClient} is thread-safe; one per instance, closed with it.
 */
public final class StoreHttp implements AutoCloseable {

  /**
   * The store's answer.
   *
   * @param status the HTTP status
   * @param errorCode S3's {@code <Code>} from an error body, or "" for a success or an empty body
   * @param body the bytes received
   */
  public record Answer(int status, String errorCode, byte[] body) {
    /** Rejects a missing code or body. */
    public Answer {
      Objects.requireNonNull(errorCode, "errorCode");
      Objects.requireNonNull(body, "body");
    }

    /** "200" or "403 AccessDenied": the status, and the error code when there is one. */
    public String summary() {
      return errorCode.isEmpty() ? Integer.toString(status) : status + " " + errorCode;
    }
  }

  // A loopback store answers in milliseconds; the timeouts only stop a stopped stack from hanging.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final int HTTP_BAD_REQUEST = 400;
  private static final Pattern ERROR_CODE = Pattern.compile("<Code>([A-Za-z]+)</Code>");
  // Set by the JDK from the URL and the body; the client refuses to set them by hand.
  private static final Set<String> SET_BY_CLIENT = Set.of("host", "content-length");

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /** A GET with no credentials at all. */
  public Answer get(String url) {
    return send(HttpRequest.newBuilder(URI.create(url)).GET(), Map.of());
  }

  /** A PUT of {@code body} with the given headers (for a signed URL, exactly the signed ones). */
  public Answer put(String url, byte[] body, Map<String, String> headers) {
    return send(
        HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)),
        headers);
  }

  @Override
  public void close() {
    client.close();
  }

  private Answer send(HttpRequest.Builder request, Map<String, String> headers) {
    headers.forEach((name, value) -> {
      if (!SET_BY_CLIENT.contains(name.toLowerCase(Locale.ROOT))) {
        request.header(name, value);
      }
    });
    try {
      HttpResponse<byte[]> response =
          client.send(
              request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofByteArray());
      Matcher code = ERROR_CODE.matcher(new String(response.body(), StandardCharsets.UTF_8));
      boolean isError = response.statusCode() >= HTTP_BAD_REQUEST && code.find();
      return new Answer(response.statusCode(), isError ? code.group(1) : "", response.body());
    } catch (IOException unreachable) {
      throw new IllegalStateException(
          "the store did not answer: " + unreachable.getMessage(), unreachable);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the store", interrupted);
    }
  }
}
