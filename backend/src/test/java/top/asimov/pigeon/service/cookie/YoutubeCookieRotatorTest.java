package top.asimov.pigeon.service.cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import top.asimov.pigeon.config.CookieRefreshProperties;

class YoutubeCookieRotatorTest {

  /**
   * Google prefixes the JSON body with an anti-hijacking sequence, so the interval has to be read
   * out of the raw text rather than a parsed document.
   */
  @Test
  void readsTheDeclaredIntervalFromAnAuthenticatedResponse() {
    String body = ")]}'\n\n[[\"identity.hfcr\",600],[\"di\",4]]";

    assertEquals(600, YoutubeCookieRotator.parseNextIntervalSeconds(body));
  }

  @Test
  void readsTheSentinelIntervalOfAnUnauthenticatedResponse() {
    String body = ")]}'\n\n[[\"identity.hfcr\",2147483647],[\"di\",5]]";

    assertEquals(2147483647, YoutubeCookieRotator.parseNextIntervalSeconds(body));
  }

  @Test
  void returnsNullWhenTheIntervalIsAbsentOrUnreadable() {
    assertNull(YoutubeCookieRotator.parseNextIntervalSeconds(null));
    assertNull(YoutubeCookieRotator.parseNextIntervalSeconds(""));
    assertNull(YoutubeCookieRotator.parseNextIntervalSeconds(")]}'\n\n[[\"di\",4]]"));
  }

  @Test
  void intervalIsClampedIntoTheConfiguredWindow() {
    CookieRefreshProperties properties = new CookieRefreshProperties();

    assertEquals(600, properties.clampIntervalSeconds(600));
    assertEquals(60, properties.clampIntervalSeconds(5));
    assertEquals(3600, properties.clampIntervalSeconds(2147483647));
    assertEquals(600, properties.clampIntervalSeconds(null));
  }
}
