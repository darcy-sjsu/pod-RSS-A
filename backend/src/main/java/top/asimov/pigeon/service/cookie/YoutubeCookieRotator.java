package top.asimov.pigeon.service.cookie;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import top.asimov.pigeon.config.CookieRefreshProperties;
import top.asimov.pigeon.config.OutboundProxyHolder;
import top.asimov.pigeon.util.NetscapeCookie;
import top.asimov.pigeon.util.SetCookieParser;

/**
 * Performs one call to Google's cookie rotation endpoint.
 *
 * <p>This is the mechanism a browser uses to keep {@code __Secure-1PSIDTS} and
 * {@code __Secure-3PSIDTS} fresh. Calling it from the backend is what lets PigeonPod own the
 * session on its own, without keeping a browser running next to yt-dlp and fighting it over
 * rotation.
 */
@Slf4j
@Component
public class YoutubeCookieRotator {

  /**
   * Google prefixes JSON responses with an anti-hijacking sequence that must be stripped before
   * parsing. The interval is declared inside as {@code ["identity.hfcr",600]}.
   */
  private static final Pattern NEXT_INTERVAL_PATTERN =
      Pattern.compile("\"identity\\.hfcr\"\\s*,\\s*(\\d+)");

  private static final String DEFAULT_COOKIE_DOMAIN = ".youtube.com";
  private static final String YOUTUBE_ORIGIN = "https://www.youtube.com";

  private final CookieRefreshProperties properties;
  private final OutboundProxyHolder outboundProxyHolder;

  public YoutubeCookieRotator(CookieRefreshProperties properties,
      OutboundProxyHolder outboundProxyHolder) {
    this.properties = properties;
    this.outboundProxyHolder = outboundProxyHolder;
  }

  public RotationResponse rotate(String cookieHeader, long nowEpochSeconds) {
    RestClient restClient = buildRestClient();
    return restClient.post()
        .uri(URI.create(properties.getRotateUrl()))
        .header("Cookie", cookieHeader)
        .header("User-Agent", properties.getUserAgent())
        .header("Origin", YOUTUBE_ORIGIN)
        .header("Referer", YOUTUBE_ORIGIN + "/")
        .contentType(MediaType.APPLICATION_JSON)
        .body(properties.getRequestBody())
        .exchange((request, response) -> {
          int statusCode = response.getStatusCode().value();
          List<String> setCookieHeaders = response.getHeaders().get("Set-Cookie");
          List<NetscapeCookie> cookies =
              SetCookieParser.parseAll(setCookieHeaders, DEFAULT_COOKIE_DOMAIN, nowEpochSeconds);
          Integer nextInterval = parseNextIntervalSeconds(readBody(response.getBody()));
          return new RotationResponse(statusCode, cookies, nextInterval);
        });
  }

  /**
   * Uses {@link SimpleClientHttpRequestFactory} rather than the JDK HTTP client because the latter
   * cannot route through a SOCKS5 proxy, and the rotation must leave from the same address as the
   * yt-dlp downloads.
   */
  private RestClient buildRestClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    Proxy proxy = outboundProxyHolder.current().toJavaNetProxy();
    if (proxy != null && proxy != Proxy.NO_PROXY) {
      requestFactory.setProxy(proxy);
    }
    Duration timeout = Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds()));
    requestFactory.setConnectTimeout(timeout);
    requestFactory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  private String readBody(InputStream body) {
    if (body == null) {
      return "";
    }
    try {
      return StreamUtils.copyToString(body, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.debug("[cookie-session] rotate response body read failed", e);
      return "";
    }
  }

  static Integer parseNextIntervalSeconds(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      return null;
    }
    Matcher matcher = NEXT_INTERVAL_PATTERN.matcher(rawBody);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public record RotationResponse(
      int statusCode,
      List<NetscapeCookie> setCookies,
      Integer nextIntervalSeconds
  ) {

  }
}
