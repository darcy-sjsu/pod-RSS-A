package top.asimov.pigeon.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable in-memory view of a Netscape/Mozilla {@code cookies.txt} file.
 *
 * <p>Comment lines, blank lines and unparseable lines are preserved verbatim and in their original
 * position, so round-tripping a user-uploaded file only changes the cookie values that were
 * explicitly merged. That matters because yt-dlp rewrites the header and drops the
 * {@code #HttpOnly_} prefix when it dumps its own jar, and because a response that clears a cookie
 * must never be able to remove long-lived credentials from the stored file.
 */
public final class NetscapeCookieFile {

  private static final String HTTP_ONLY_PREFIX = "#HttpOnly_";
  private static final int ENTRY_FIELD_COUNT = 7;
  private static final String DEFAULT_HEADER = "# Netscape HTTP Cookie File";

  /**
   * Domains that hold the same YouTube session. A rotated value must be applied to every one of
   * them that is already present, because yt-dlp reads {@code .youtube.com} while the rotation
   * endpoint may answer for {@code .google.com}.
   */
  private static final List<String> PREFERRED_DOMAINS = List.of(".youtube.com", ".google.com");

  private final List<Entry> entries;

  private NetscapeCookieFile(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  public static NetscapeCookieFile parse(String content) {
    List<Entry> parsed = new ArrayList<>();
    if (content == null || content.isBlank()) {
      return new NetscapeCookieFile(parsed);
    }

    for (String rawLine : content.split("\n", -1)) {
      String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
      NetscapeCookie cookie = parseCookieLine(line);
      parsed.add(cookie == null ? Entry.raw(line) : Entry.cookie(cookie));
    }
    return new NetscapeCookieFile(parsed);
  }

  public String serialize() {
    if (entries.isEmpty()) {
      return DEFAULT_HEADER + "\n";
    }
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) {
        builder.append('\n');
      }
      builder.append(entries.get(i).render());
    }
    return builder.toString();
  }

  public List<NetscapeCookie> cookies() {
    return entries.stream().filter(Entry::isCookie).map(Entry::cookie).toList();
  }

  public boolean hasCookie(String name) {
    return cookies().stream().anyMatch(cookie -> cookie.name().equalsIgnoreCase(name));
  }

  public boolean hasAnyCookie(Collection<String> names) {
    return names.stream().anyMatch(this::hasCookie);
  }

  /**
   * Builds a {@code Cookie} request header holding only the requested names, preferring the
   * YouTube domain when the same name exists for several domains.
   */
  public String toCookieHeader(Collection<String> names) {
    List<String> pairs = new ArrayList<>();
    for (String name : names) {
      NetscapeCookie cookie = findPreferred(name);
      if (cookie != null) {
        pairs.add(cookie.name() + "=" + cookie.value());
      }
    }
    return String.join("; ", pairs);
  }

  /**
   * Applies candidate cookies onto this file.
   *
   * <p>Only names in {@code allowedNames} are considered, a candidate without a value or with an
   * expiry in the past is ignored (it is a deletion instruction), and a name that is absent from
   * the candidates is left untouched. A candidate that matches existing entries updates all of
   * them, so the {@code .youtube.com} and {@code .google.com} copies of a rotated token stay in
   * sync.
   */
  public MergeResult merge(Collection<NetscapeCookie> candidates, Collection<String> allowedNames,
      long nowEpochSeconds) {
    Set<String> allowed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    allowed.addAll(allowedNames);

    List<Entry> merged = new ArrayList<>(entries);
    Set<String> updatedNames = new LinkedHashSet<>();
    Set<String> updatedDomains = new LinkedHashSet<>();
    List<String> rejectedNames = new ArrayList<>();

    for (NetscapeCookie candidate : candidates) {
      if (candidate == null || candidate.name() == null) {
        continue;
      }
      if (!allowed.contains(candidate.name())) {
        rejectedNames.add(candidate.name());
        continue;
      }
      if (candidate.value() == null || candidate.value().isBlank()) {
        rejectedNames.add(candidate.name());
        continue;
      }
      if (candidate.expiresAt() > 0L && candidate.expiresAt() <= nowEpochSeconds) {
        rejectedNames.add(candidate.name());
        continue;
      }

      boolean matchedExisting = false;
      for (int i = 0; i < merged.size(); i++) {
        Entry entry = merged.get(i);
        if (!entry.isCookie() || !entry.cookie().name().equalsIgnoreCase(candidate.name())) {
          continue;
        }
        matchedExisting = true;
        NetscapeCookie existing = entry.cookie();
        long expiresAt = candidate.expiresAt() > 0L ? candidate.expiresAt() : existing.expiresAt();
        if (existing.value().equals(candidate.value()) && existing.expiresAt() == expiresAt) {
          continue;
        }
        merged.set(i, Entry.cookie(existing.withValue(candidate.value(), expiresAt)));
        updatedNames.add(existing.name());
        updatedDomains.add(existing.domain());
      }

      if (!matchedExisting) {
        merged.add(Entry.cookie(candidate));
        updatedNames.add(candidate.name());
        updatedDomains.add(candidate.domain());
      }
    }

    boolean changed = !updatedNames.isEmpty();
    NetscapeCookieFile mergedFile = changed ? new NetscapeCookieFile(merged) : this;
    return new MergeResult(mergedFile, List.copyOf(updatedNames), List.copyOf(updatedDomains),
        List.copyOf(rejectedNames), changed);
  }

  private NetscapeCookie findPreferred(String name) {
    for (String domain : PREFERRED_DOMAINS) {
      for (NetscapeCookie cookie : cookies()) {
        if (cookie.name().equalsIgnoreCase(name) && domain.equalsIgnoreCase(cookie.domain())) {
          return cookie;
        }
      }
    }
    return cookies().stream()
        .filter(cookie -> cookie.name().equalsIgnoreCase(name))
        .findFirst()
        .orElse(null);
  }

  private static NetscapeCookie parseCookieLine(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    String candidate = line;
    boolean httpOnly = false;
    if (candidate.startsWith(HTTP_ONLY_PREFIX)) {
      candidate = candidate.substring(HTTP_ONLY_PREFIX.length());
      httpOnly = true;
    } else if (candidate.startsWith("#")) {
      return null;
    }

    String[] fields = candidate.split("\t", -1);
    if (fields.length != ENTRY_FIELD_COUNT) {
      return null;
    }
    long expiresAt;
    try {
      String rawExpiry = fields[4].trim();
      expiresAt = rawExpiry.isEmpty() ? 0L : Long.parseLong(rawExpiry);
    } catch (NumberFormatException e) {
      return null;
    }
    return new NetscapeCookie(
        fields[0],
        parseBoolean(fields[1]),
        fields[2],
        parseBoolean(fields[3]),
        expiresAt,
        fields[5],
        fields[6],
        httpOnly);
  }

  private static boolean parseBoolean(String rawValue) {
    return "TRUE".equalsIgnoreCase(rawValue == null ? null : rawValue.trim());
  }

  private static String renderBoolean(boolean value) {
    return value ? "TRUE" : "FALSE";
  }

  public record MergeResult(
      NetscapeCookieFile file,
      List<String> updatedNames,
      List<String> updatedDomains,
      List<String> rejectedNames,
      boolean changed
  ) {

  }

  private record Entry(String rawLine, NetscapeCookie cookie) {

    private static Entry raw(String rawLine) {
      return new Entry(rawLine, null);
    }

    private static Entry cookie(NetscapeCookie cookie) {
      return new Entry(null, cookie);
    }

    private boolean isCookie() {
      return cookie != null;
    }

    private String render() {
      if (cookie == null) {
        return rawLine;
      }
      return (cookie.httpOnly() ? HTTP_ONLY_PREFIX : "")
          + cookie.domain() + '\t'
          + renderBoolean(cookie.includeSubdomains()) + '\t'
          + cookie.path() + '\t'
          + renderBoolean(cookie.secure()) + '\t'
          + cookie.expiresAt() + '\t'
          + cookie.name() + '\t'
          + cookie.value();
    }
  }

  /**
   * Names of the cookies the backend is allowed to refresh. They are the short-lived freshness
   * tokens; long-lived credentials such as {@code LOGIN_INFO} or {@code __Secure-1PSID} are
   * deliberately excluded so no external response can drop them.
   */
  public static Set<String> rotatableCookieNames() {
    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    names.addAll(List.of(
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS",
        "SIDCC",
        "__Secure-1PSIDCC",
        "__Secure-3PSIDCC"));
    return names;
  }

  /**
   * Cookies sent to the rotation endpoint. Only the identifiers it needs, never the whole jar.
   */
  public static List<String> rotationRequestCookieNames() {
    return List.of("__Secure-1PSID", "__Secure-3PSID", "__Secure-1PSIDTS", "__Secure-3PSIDTS");
  }

  /**
   * Cookies yt-dlp requires to consider the jar authenticated. Mirrors
   * {@code YoutubeBaseInfoExtractor._has_auth_cookies}: LOGIN_INFO plus one SAPISID variant.
   */
  public static List<String> sapisidCookieNames() {
    return List.of("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID");
  }

  public static String loginInfoCookieName() {
    return "LOGIN_INFO";
  }

  public boolean hasYoutubeAuthCookies() {
    return hasCookie(loginInfoCookieName()) && hasAnyCookie(sapisidCookieNames());
  }

  @Override
  public String toString() {
    return "NetscapeCookieFile[cookies=" + cookies().size() + "]";
  }

  public String describeCookieNames() {
    return cookies().stream()
        .map(cookie -> cookie.name().toLowerCase(Locale.ROOT))
        .distinct()
        .sorted()
        .toList()
        .toString();
  }
}
