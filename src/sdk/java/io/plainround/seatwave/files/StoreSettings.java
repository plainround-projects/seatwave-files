package io.plainround.seatwave.files;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Where the lab's store and database are, and the credentials to reach them: {@code
 * config/store.properties} for the addresses, files in {@code .secrets/} for the keys and the
 * password (written by {@code scripts/stack.sh up}, never committed).
 *
 * <p>Thread-safety: immutable.
 *
 * @param endpoint the S3 endpoint, for example {@code http://127.0.0.1:22900}
 * @param region the signing region
 * @param accessKey the store's access key id
 * @param secretKey the store's secret key
 * @param jdbcUrl the PostgreSQL URL of the database that holds {@code stored_objects}
 * @param databaseUser the role that owns {@code stored_objects}
 * @param databasePassword that role's password
 */
public record StoreSettings(
    URI endpoint,
    String region,
    String accessKey,
    String secretKey,
    String jdbcUrl,
    String databaseUser,
    String databasePassword) {

  /** The settings file, relative to the project folder. */
  public static final String SETTINGS_FILE = "config/store.properties";

  private static final String SECRETS = ".secrets";
  private static final String ACCESS_KEY_FILE = "store-access-key";
  private static final String SECRET_KEY_FILE = "store-secret-key";
  private static final String DATABASE_PASSWORD_FILE = "owner.password";
  private static final String REDACTED = "<redacted>";

  /** Rejects a missing field; {@link #load} is the way to build one from files. */
  public StoreSettings {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(accessKey, "accessKey");
    Objects.requireNonNull(secretKey, "secretKey");
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(databaseUser, "databaseUser");
    Objects.requireNonNull(databasePassword, "databasePassword");
  }

  /**
   * Reads the settings file and the three secret files under {@code projectFolder}.
   *
   * @return the settings, or every problem found (all of them, not only the first)
   */
  public static Result<StoreSettings, List<String>> load(Path projectFolder) {
    List<String> problems = new ArrayList<>();
    Properties properties = new Properties();
    Path settingsFile = projectFolder.resolve(SETTINGS_FILE);
    try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException unreadable) {
      problems.add("cannot read " + SETTINGS_FILE + ": " + unreadable.getMessage());
    }
    Optional<URI> endpoint = endpoint(properties.getProperty("endpoint", ""), problems);
    String region = required(properties, "region", problems);
    String jdbcUrl = required(properties, "jdbcUrl", problems);
    if (!jdbcUrl.isEmpty() && !jdbcUrl.startsWith("jdbc:postgresql://")) {
      problems.add("jdbcUrl must start with jdbc:postgresql://, was " + jdbcUrl);
    }
    String databaseUser = required(properties, "databaseUser", problems);
    Path secrets = projectFolder.resolve(SECRETS);
    String accessKey = secret(secrets.resolve(ACCESS_KEY_FILE), problems);
    String secretKey = secret(secrets.resolve(SECRET_KEY_FILE), problems);
    String password = secret(secrets.resolve(DATABASE_PASSWORD_FILE), problems);
    if (!problems.isEmpty() || endpoint.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(
        new StoreSettings(
            endpoint.get(), region, accessKey, secretKey, jdbcUrl, databaseUser, password));
  }

  /** Names the endpoint and the database, never a credential. */
  @Override
  public String toString() {
    return String.format(
        Locale.ROOT,
        "StoreSettings[endpoint=%s, region=%s, accessKey=%s, secretKey=%s, jdbcUrl=%s,"
            + " databaseUser=%s, databasePassword=%s]",
        endpoint,
        region,
        REDACTED,
        REDACTED,
        jdbcUrl,
        databaseUser,
        REDACTED);
  }

  private static Optional<URI> endpoint(String text, List<String> problems) {
    if (text.isBlank()) {
      problems.add("endpoint is missing from " + SETTINGS_FILE);
      return Optional.empty();
    }
    try {
      URI endpoint = new URI(text.strip());
      if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())
          || endpoint.getHost() == null) {
        problems.add("endpoint must be an http or https URL with a host, was " + text);
        return Optional.empty();
      }
      return Optional.of(endpoint);
    } catch (URISyntaxException malformed) {
      problems.add("endpoint is not a URL: " + text);
      return Optional.empty();
    }
  }

  private static String required(Properties properties, String name, List<String> problems) {
    String value = properties.getProperty(name, "").strip();
    if (value.isEmpty()) {
      problems.add(name + " is missing from " + SETTINGS_FILE);
    }
    return value;
  }

  // The file holds one line; a missing or empty file means the stack was never started.
  private static String secret(Path file, List<String> problems) {
    String name = SECRETS + "/" + file.getFileName();
    try {
      String value = Files.readString(file, StandardCharsets.UTF_8).strip();
      if (value.isEmpty()) {
        problems.add(name + " is empty; run scripts/stack.sh up --run first");
      }
      return value;
    } catch (IOException unreadable) {
      problems.add(name + " cannot be read; run scripts/stack.sh up --run first");
      return "";
    }
  }
}
