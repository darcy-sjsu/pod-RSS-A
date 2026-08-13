package top.asimov.pigeon.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SetCookieParserTest {

  private static final long NOW = 1_800_000_000L;

  @Test
  void parsesRotatedTokenWithMaxAge() {
    List<NetscapeCookie> cookies = SetCookieParser.parse(
        "__Secure-1PSIDTS=sidts-fresh; Domain=.google.com; Path=/; Max-Age=63072000; Secure; "
            + "HttpOnly; SameSite=None; Priority=HIGH",
        ".youtube.com", NOW);

    assertEquals(1, cookies.size());
    NetscapeCookie cookie = cookies.get(0);
    assertEquals("__Secure-1PSIDTS", cookie.name());
    assertEquals("sidts-fresh", cookie.value());
    assertEquals(".google.com", cookie.domain());
    assertTrue(cookie.secure());
    assertTrue(cookie.httpOnly());
    assertEquals(NOW + 63_072_000L, cookie.expiresAt());
  }

  @Test
  void parsesExpiresDateFormat() {
    List<NetscapeCookie> cookies = SetCookieParser.parse(
        "__Secure-3PSIDTS=sidts-3p; Domain=.youtube.com; Path=/; "
            + "Expires=Fri, 12-Feb-2027 16:28:10 GMT; Secure",
        ".youtube.com", NOW);

    assertEquals(1, cookies.size());
    assertTrue(cookies.get(0).expiresAt() > NOW);
  }

  @Test
  void mapsDeletionInstructionToAnAlreadyExpiredTimestamp() {
    List<NetscapeCookie> cookies = SetCookieParser.parse(
        "LOGIN_INFO=; Domain=.youtube.com; Path=/; Max-Age=0; Secure; HttpOnly",
        ".youtube.com", NOW);

    assertEquals(1, cookies.size());
    assertEquals(NOW - 1L, cookies.get(0).expiresAt());
  }

  @Test
  void deletionInstructionCannotRemoveAStoredCookie() {
    NetscapeCookieFile stored = NetscapeCookieFile.parse(String.join("\n",
        "# Netscape HTTP Cookie File",
        ".youtube.com\tTRUE\t/\tTRUE\t1900000000\t__Secure-1PSIDTS\tkeep-me"));
    List<NetscapeCookie> incoming = SetCookieParser.parse(
        "__Secure-1PSIDTS=; Domain=.youtube.com; Path=/; Max-Age=0; Secure", ".youtube.com", NOW);

    NetscapeCookieFile.MergeResult result = stored.merge(incoming,
        NetscapeCookieFile.rotatableCookieNames(), NOW);

    assertFalse(result.changed());
    assertTrue(result.file().serialize().contains("__Secure-1PSIDTS\tkeep-me"));
  }

  @Test
  void fallsBackToTheRequestDomainAndNormalizesTheLeadingDot() {
    List<NetscapeCookie> cookies = SetCookieParser.parse("SIDCC=value; Path=/", ".youtube.com", NOW);

    assertEquals(".youtube.com", cookies.get(0).domain());
    assertEquals(0L, cookies.get(0).expiresAt());

    List<NetscapeCookie> withoutDot =
        SetCookieParser.parse("SIDCC=value; Domain=google.com; Path=/", ".youtube.com", NOW);
    assertEquals(".google.com", withoutDot.get(0).domain());
  }

  @Test
  void ignoresMalformedHeaders() {
    assertTrue(SetCookieParser.parse("   ", ".youtube.com", NOW).isEmpty());
    assertTrue(SetCookieParser.parseAll(null, ".youtube.com", NOW).isEmpty());
  }
}
