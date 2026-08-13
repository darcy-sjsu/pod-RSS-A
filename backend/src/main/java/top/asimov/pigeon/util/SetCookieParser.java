package top.asimov.pigeon.util;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts HTTP {@code Set-Cookie} response headers into {@link NetscapeCookie} entries.
 *
 * <p>Parsing is delegated to {@link HttpCookie}, which already normalizes the two {@code Expires}
 * date formats and the {@code Max-Age} attribute into a single max-age value.
 */
public final class SetCookieParser {

  private SetCookieParser() {
  }

  public static List<NetscapeCookie> parseAll(List<String> setCookieHeaders, String defaultDomain,
      long nowEpochSeconds) {
    List<NetscapeCookie> cookies = new ArrayList<>();
    if (setCookieHeaders == null) {
      return cookies;
    }
    for (String header : setCookieHeaders) {
      cookies.addAll(parse(header, defaultDomain, nowEpochSeconds));
    }
    return cookies;
  }

  public static List<NetscapeCookie> parse(String setCookieHeader, String defaultDomain,
      long nowEpochSeconds) {
    List<NetscapeCookie> cookies = new ArrayList<>();
    if (setCookieHeader == null || setCookieHeader.isBlank()) {
      return cookies;
    }

    List<HttpCookie> parsed;
    try {
      parsed = HttpCookie.parse(setCookieHeader);
    } catch (IllegalArgumentException e) {
      return cookies;
    }

    for (HttpCookie httpCookie : parsed) {
      String domain = normalizeDomain(httpCookie.getDomain(), defaultDomain);
      String path = httpCookie.getPath() == null || httpCookie.getPath().isBlank()
          ? "/"
          : httpCookie.getPath();
      cookies.add(new NetscapeCookie(
          domain,
          domain.startsWith("."),
          path,
          httpCookie.getSecure(),
          toExpiresAt(httpCookie.getMaxAge(), nowEpochSeconds),
          httpCookie.getName(),
          httpCookie.getValue() == null ? "" : httpCookie.getValue(),
          httpCookie.isHttpOnly()));
    }
    return cookies;
  }

  /**
   * {@link HttpCookie#getMaxAge()} returns a negative value for a session cookie and {@code 0} for
   * a deletion instruction. A deletion is mapped to an already-expired timestamp so the merge
   * rejects it instead of wiping a stored cookie.
   */
  private static long toExpiresAt(long maxAge, long nowEpochSeconds) {
    if (maxAge < 0L) {
      return 0L;
    }
    if (maxAge == 0L) {
      return nowEpochSeconds - 1L;
    }
    return nowEpochSeconds + maxAge;
  }

  private static String normalizeDomain(String rawDomain, String defaultDomain) {
    String domain = rawDomain == null || rawDomain.isBlank() ? defaultDomain : rawDomain.trim();
    if (domain == null || domain.isBlank()) {
      return "";
    }
    return domain.startsWith(".") ? domain : "." + domain;
  }
}
