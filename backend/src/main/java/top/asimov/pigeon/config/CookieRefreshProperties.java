package top.asimov.pigeon.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for keeping the stored YouTube session fresh.
 *
 * <p>The endpoint and request body are configurable because they are a private Google interface:
 * if it changes, an operator can adjust the configuration instead of waiting for a release.
 */
@Data
@Component
@ConfigurationProperties(prefix = "pigeon.cookie.refresh")
public class CookieRefreshProperties {

  private boolean enabled = true;

  private String rotateUrl = "https://accounts.youtube.com/RotateCookies";

  private String requestBody = "[0,\"-0000000000000000000\"]";

  /**
   * Google answers 429 when the rotation is called more than once a minute.
   */
  private int minIntervalSeconds = 60;

  /**
   * Used when the response does not declare its own interval. Google normally declares 600.
   */
  private int defaultIntervalSeconds = 600;

  private int maxIntervalSeconds = 3600;

  private int maxConsecutiveFailures = 3;

  private int requestTimeoutSeconds = 15;

  private int verifyTimeoutSeconds = 60;

  /**
   * Sent with the rotation request so the session is not seen switching between a browser
   * fingerprint and a Java default one.
   */
  private String userAgent =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/140.0.0.0 Safari/537.36";

  public int clampIntervalSeconds(Integer rawSeconds) {
    int seconds = rawSeconds == null ? defaultIntervalSeconds : rawSeconds;
    if (seconds < minIntervalSeconds) {
      return minIntervalSeconds;
    }
    return Math.min(seconds, maxIntervalSeconds);
  }
}
