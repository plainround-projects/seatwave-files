package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A key named by the SHA-256 of the bytes names exactly those bytes. */
class ContentAddressTest {

  private static final byte[] POSTER = "a poster".getBytes(StandardCharsets.UTF_8);

  @Test
  void givesTheSameBytesTheSameKey() {
    assertThat(ContentAddress.keyFor("posters/1", POSTER.clone(), "jpg"))
        .isEqualTo(ContentAddress.keyFor("posters/1", POSTER, "jpg"))
        .matches("^posters/1/[0-9a-f]{64}\\.jpg$");
  }

  @Test
  void catchesOneFlippedBitOnRead() {
    String key = ContentAddress.keyFor("posters/1", POSTER, "jpg");
    byte[] readBack = POSTER.clone();
    readBack[0] ^= 1;

    assertThat(ContentAddress.matches(key, readBack)).isFalse();
    assertThat(ContentAddress.matches(key, POSTER)).isTrue();
  }

  @Test
  void refusesAKeyWithoutAFileName() {
    assertThat(ContentAddress.matches("no-slash.jpg", POSTER)).isFalse();
  }

  @Test
  void keepsModuleSeventeensTwelveDigitCdnName() {
    assertThat(ContentAddress.cdnName("poster-arena", POSTER, "jpg"))
        .isEqualTo(
            "poster-arena."
                + ContentAddress.sha256Hex(POSTER).substring(0, ContentAddress.CDN_HEX_DIGITS)
                + ".jpg");
  }

  @Test
  void rejectsAPrefixThatEndsInASlashAndAnExtensionNotAllowed() {
    assertThatThrownBy(() -> ContentAddress.keyFor("posters/", POSTER, "jpg"))
        .hasMessage("prefix not allowed: posters/");
    assertThatThrownBy(() -> ContentAddress.keyFor("posters/1", POSTER, "svg"))
        .hasMessage("extension not allowed: svg");
  }
}
