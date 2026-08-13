package top.asimov.pigeon.model.enums;

/**
 * Lifecycle state of a stored platform cookie session.
 */
public enum CookieSessionStatus {

  /**
   * Freshly uploaded or never probed. Cookies are used as-is until something proves otherwise.
   */
  UNKNOWN,

  /**
   * The last refresh succeeded, so the session is known to be alive.
   */
  ACTIVE,

  /**
   * A refresh attempt failed but the stored credentials may still work. Downloads keep running.
   */
  STALE,

  /**
   * The session is confirmed dead and needs a new sign-in. Downloads still run without blocking,
   * because a false positive must never take the whole install offline.
   */
  INVALID;

  public static CookieSessionStatus fromNullable(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return UNKNOWN;
    }
    try {
      return valueOf(rawValue.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
  }
}
