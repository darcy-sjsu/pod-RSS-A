package top.asimov.pigeon.util;

/**
 * A single entry of a Netscape/Mozilla {@code cookies.txt} file.
 *
 * @param domain            cookie domain, for example {@code .youtube.com}
 * @param includeSubdomains whether the domain applies to subdomains
 * @param path              cookie path
 * @param secure            whether the cookie is HTTPS only
 * @param expiresAt         expiry as epoch seconds; {@code 0} marks a session cookie
 * @param name              cookie name
 * @param value             cookie value
 * @param httpOnly          whether the entry carried the {@code #HttpOnly_} domain prefix
 */
public record NetscapeCookie(
    String domain,
    boolean includeSubdomains,
    String path,
    boolean secure,
    long expiresAt,
    String name,
    String value,
    boolean httpOnly
) {

  public NetscapeCookie withValue(String newValue, long newExpiresAt) {
    return new NetscapeCookie(domain, includeSubdomains, path, secure, newExpiresAt, name, newValue,
        httpOnly);
  }

  public boolean isSessionCookie() {
    return expiresAt <= 0L;
  }
}
