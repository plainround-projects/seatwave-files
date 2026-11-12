package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link ObjectStore} applies to the lab's store are the rules of {@code
 * lifecycle/seatwave-media.json}, less the one transition the store cannot take.
 */
class LifecycleFileTest {

  private static final Pattern RULE_ID = Pattern.compile("\"ID\":\\s*\"([^\"]+)\"");

  @Test
  void appliesEveryRuleOfTheFileButTheTransition() throws IOException {
    String file =
        Files.readString(Path.of("lifecycle/seatwave-media.json"), StandardCharsets.UTF_8);

    var ids = RULE_ID.matcher(file).results().map(found -> found.group(1)).toList();

    assertThat(ids)
        .containsExactly(
            ObjectStore.DRAFTS_RULE, ObjectStore.ABORT_RULE, ObjectStore.TRANSITION_RULE);
  }
}
