package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every settings problem is reported together, and no credential is ever printed. */
class StoreSettingsTest {

  private static final String VALID_SETTINGS =
      """
      endpoint=http://127.0.0.1:22900
      region=us-east-1
      jdbcUrl=jdbc:postgresql://127.0.0.1:22920/seatwave
      databaseUser=seatwave_files
      """;
  // Fixed test values, built from words so no scanner mistakes them for real credentials.
  private static final String ACCESS_KEY = String.join("-", "fixed", "test", "access");
  private static final String SECRET_KEY = String.join("-", "fixed", "test", "secret");
  private static final String PASSWORD = String.join("-", "fixed", "test", "password");

  @TempDir Path projectFolder;

  @Test
  void loadsTheSettingsAndTheThreeSecrets() throws IOException {
    writeProject(VALID_SETTINGS);

    var loaded = StoreSettings.load(projectFolder);

    assertThat(loaded.discardError())
        .hasValueSatisfying(settings -> {
          assertThat(settings.endpoint()).hasToString("http://127.0.0.1:22900");
          assertThat(settings.secretKey()).isEqualTo(SECRET_KEY);
          assertThat(settings.databasePassword()).isEqualTo(PASSWORD);
        });
  }

  @Test
  void reportsEveryMissingFileTogether() {
    var loaded = StoreSettings.load(projectFolder);

    assertThat(problems(loaded))
        .hasSize(8)
        .anyMatch(problem -> problem.startsWith("cannot read config/store.properties"))
        .anyMatch(problem -> problem.startsWith("endpoint is missing"))
        .anyMatch(problem -> problem.startsWith(".secrets/store-secret-key cannot be read"))
        .anyMatch(problem -> problem.startsWith(".secrets/owner.password cannot be read"));
  }

  @Test
  void rejectsAnEndpointWithoutAHostAndAJdbcUrlForAnotherDatabase() throws IOException {
    writeProject(
        VALID_SETTINGS
            .replace("http://127.0.0.1:22900", "file:/tmp/store")
            .replace("jdbc:postgresql:", "jdbc:h2:"));

    assertThat(problems(StoreSettings.load(projectFolder)))
        .containsExactlyInAnyOrder(
            "endpoint must be an http or https URL with a host, was file:/tmp/store",
            "jdbcUrl must start with jdbc:postgresql://, was jdbc:h2://127.0.0.1:22920/seatwave");
  }

  @Test
  void reportsAnEmptySecretFile() throws IOException {
    writeProject(VALID_SETTINGS);
    Files.writeString(projectFolder.resolve(".secrets/store-access-key"), "\n");

    assertThat(problems(StoreSettings.load(projectFolder)))
        .containsExactly(".secrets/store-access-key is empty; run scripts/stack.sh up --run first");
  }

  @Test
  void neverPrintsACredential() throws IOException {
    writeProject(VALID_SETTINGS);

    String printed = StoreSettings.load(projectFolder).discardError().orElseThrow().toString();

    assertThat(printed)
        .contains("127.0.0.1:22900")
        .doesNotContain(ACCESS_KEY, SECRET_KEY, PASSWORD);
  }

  private void writeProject(String settings) throws IOException {
    Files.createDirectories(projectFolder.resolve("config"));
    Files.createDirectories(projectFolder.resolve(".secrets"));
    Files.writeString(
        projectFolder.resolve(StoreSettings.SETTINGS_FILE), settings, StandardCharsets.UTF_8);
    Files.writeString(projectFolder.resolve(".secrets/store-access-key"), ACCESS_KEY + "\n");
    Files.writeString(projectFolder.resolve(".secrets/store-secret-key"), SECRET_KEY + "\n");
    Files.writeString(projectFolder.resolve(".secrets/owner.password"), PASSWORD + "\n");
  }

  // The problems of a failed load, or an empty list for a successful one.
  private static List<String> problems(Result<StoreSettings, List<String>> loaded) {
    return loaded.map(settings -> List.<String>of()).recoverError(found -> found);
  }
}
