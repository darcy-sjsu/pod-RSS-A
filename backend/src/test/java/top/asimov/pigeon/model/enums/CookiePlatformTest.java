package top.asimov.pigeon.model.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CookiePlatformTest {

  @Test
  void resolvesYoutubeSourceWithoutCaseOrWhitespaceSensitivity() {
    assertEquals(CookiePlatform.YOUTUBE, CookiePlatform.fromFeedSource("YOUTUBE"));
    assertEquals(CookiePlatform.YOUTUBE, CookiePlatform.fromFeedSource("youtube"));
    assertEquals(CookiePlatform.YOUTUBE, CookiePlatform.fromFeedSource(" YouTube "));
  }

  @Test
  void rejectsMissingOrUnsupportedSources() {
    assertNull(CookiePlatform.fromFeedSource(null));
    assertNull(CookiePlatform.fromFeedSource(""));
    assertNull(CookiePlatform.fromFeedSource("RUMBLE"));
  }
}
