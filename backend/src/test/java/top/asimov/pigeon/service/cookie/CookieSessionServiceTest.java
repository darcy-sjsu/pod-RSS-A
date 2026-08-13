package top.asimov.pigeon.service.cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import top.asimov.pigeon.config.CookieRefreshProperties;
import top.asimov.pigeon.helper.CookieSessionLocks;
import top.asimov.pigeon.mapper.CookieConfigMapper;
import top.asimov.pigeon.model.entity.CookieConfig;
import top.asimov.pigeon.model.enums.CookiePlatform;
import top.asimov.pigeon.model.enums.CookieSessionStatus;
import top.asimov.pigeon.model.response.CookieRotationResponse;
import top.asimov.pigeon.service.CookieService;
import top.asimov.pigeon.service.YtDlpProxyService;
import top.asimov.pigeon.service.YtDlpRuntimeService;
import top.asimov.pigeon.service.notification.CookieSessionNotifyService;
import top.asimov.pigeon.util.NetscapeCookie;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CookieSessionServiceTest {

  private static final String COOKIES = String.join("\n",
      "# Netscape HTTP Cookie File",
      "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1900000000\tLOGIN_INFO\tlogin-value",
      ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tSAPISID\tsapisid-value",
      ".youtube.com\tTRUE\t/\tTRUE\t1900000000\t__Secure-1PSID\tpsid-value",
      ".youtube.com\tTRUE\t/\tTRUE\t1850000000\t__Secure-1PSIDTS\told-1psidts");

  @Mock
  private CookieConfigMapper cookieConfigMapper;
  @Mock
  private CookieService cookieService;
  @Mock
  private YoutubeCookieRotator youtubeCookieRotator;
  @Mock
  private CookieSessionNotifyService cookieSessionNotifyService;
  @Mock
  private YtDlpRuntimeService ytDlpRuntimeService;
  @Mock
  private YtDlpProxyService ytDlpProxyService;

  private CookieConfig storedConfig;
  private CookieSessionService cookieSessionService;

  @BeforeEach
  void setUp() {
    storedConfig = CookieConfig.builder()
        .id(1L)
        .platform(CookiePlatform.YOUTUBE.name())
        .cookiesContent(COOKIES)
        .enabled(true)
        .sourceType(CookieService.SOURCE_TYPE_UPLOAD)
        .sessionStatus(CookieSessionStatus.UNKNOWN.name())
        .autoRefreshEnabled(true)
        .rotateIntervalSeconds(600)
        .rotateFailureCount(0)
        .updatedAt(LocalDateTime.now())
        .build();

    when(cookieService.managedPlatforms()).thenReturn(List.of(CookiePlatform.YOUTUBE));
    when(cookieService.findConfig(CookiePlatform.YOUTUBE)).thenAnswer(invocation -> storedConfig);
    when(cookieConfigMapper.selectOne(any())).thenAnswer(invocation -> storedConfig);
    doAnswer(invocation -> 1).when(cookieConfigMapper).updateById(any(CookieConfig.class));

    cookieSessionService = new CookieSessionService(cookieConfigMapper, cookieService,
        new CookieSessionLocks(), new CookieRefreshProperties(), youtubeCookieRotator,
        cookieSessionNotifyService, ytDlpRuntimeService, ytDlpProxyService);
  }

  @Test
  void successfulRotationStoresTheFreshTokenAndSchedulesTheDeclaredInterval() {
    when(youtubeCookieRotator.rotate(anyString(), anyLong())).thenReturn(
        new YoutubeCookieRotator.RotationResponse(200,
            List.of(rotatedCookie("__Secure-1PSIDTS", "fresh-1psidts")), 600));

    CookieRotationResponse response = cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);

    assertEquals("ROTATED", response.getOutcome());
    assertEquals(CookieSessionStatus.ACTIVE.name(), response.getSessionStatus());
    assertEquals(List.of("__Secure-1PSIDTS"), response.getRotatedCookieNames());
    assertEquals(600, response.getNextIntervalSeconds());
    assertTrue(storedConfig.getCookiesContent().contains("fresh-1psidts"));
    assertTrue(storedConfig.getCookiesContent().contains("#HttpOnly_.youtube.com"));
    assertEquals(CookieService.SOURCE_TYPE_ROTATED, storedConfig.getSourceType());
    assertEquals(0, storedConfig.getRotateFailureCount());
    assertNotNull(storedConfig.getNextRotateAt());
  }

  @Test
  void rotationOnlySendsTheIdentifiersTheEndpointNeeds() {
    when(youtubeCookieRotator.rotate(anyString(), anyLong())).thenReturn(
        new YoutubeCookieRotator.RotationResponse(200,
            List.of(rotatedCookie("__Secure-1PSIDTS", "fresh-1psidts")), 600));

    cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);

    ArgumentCaptor<String> cookieHeader = ArgumentCaptor.forClass(String.class);
    verify(youtubeCookieRotator).rotate(cookieHeader.capture(), anyLong());
    assertEquals("__Secure-1PSID=psid-value; __Secure-1PSIDTS=old-1psidts",
        cookieHeader.getValue());
  }

  @Test
  void rejectionBelowTheThresholdOnlyDowngradesToStale() {
    when(youtubeCookieRotator.rotate(anyString(), anyLong())).thenReturn(
        new YoutubeCookieRotator.RotationResponse(403, List.of(), null));

    CookieRotationResponse response = cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);

    assertEquals("FAILED", response.getOutcome());
    assertEquals("HTTP_403", response.getReason());
    assertEquals(CookieSessionStatus.STALE.name(), storedConfig.getSessionStatus());
    assertEquals(1, storedConfig.getRotateFailureCount());
    assertTrue(storedConfig.getCookiesContent().contains("login-value"));
    verify(cookieSessionNotifyService, never()).notifySessionInvalidated(any(), anyString());
  }

  @Test
  void repeatedRejectionsInvalidateTheSessionAndNotifyOnce() {
    when(youtubeCookieRotator.rotate(anyString(), anyLong())).thenReturn(
        new YoutubeCookieRotator.RotationResponse(403, List.of(), null));

    cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);
    cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);
    cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);
    cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);

    assertEquals(CookieSessionStatus.INVALID.name(), storedConfig.getSessionStatus());
    assertEquals(4, storedConfig.getRotateFailureCount());
    assertTrue(storedConfig.getCookiesContent().contains("login-value"));
    verify(cookieSessionNotifyService, times(1))
        .notifySessionInvalidated(CookiePlatform.YOUTUBE, "HTTP_403");
  }

  @Test
  void rateLimitingBacksOffWithoutCountingAsARejection() {
    when(youtubeCookieRotator.rotate(anyString(), anyLong())).thenReturn(
        new YoutubeCookieRotator.RotationResponse(429, List.of(), null));

    CookieRotationResponse response = cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);

    assertEquals("RATE_LIMITED", response.getReason());
    assertEquals(CookieSessionStatus.STALE.name(), storedConfig.getSessionStatus());
    assertEquals(0, storedConfig.getRotateFailureCount());
    assertTrue(storedConfig.getNextRotateAt().isAfter(LocalDateTime.now().plusSeconds(240)));
  }

  @Test
  void missingAuthCookiesInvalidateWithoutCallingTheEndpoint() {
    storedConfig.setCookiesContent(String.join("\n",
        "# Netscape HTTP Cookie File",
        ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tSAPISID\tsapisid-value"));

    CookieRotationResponse response = cookieSessionService.rotate(CookiePlatform.YOUTUBE, true);

    assertEquals("MISSING_AUTH_COOKIES", response.getReason());
    assertEquals(CookieSessionStatus.INVALID.name(), storedConfig.getSessionStatus());
    verify(youtubeCookieRotator, never()).rotate(anyString(), anyLong());
    verify(cookieSessionNotifyService, times(1))
        .notifySessionInvalidated(CookiePlatform.YOUTUBE, "MISSING_AUTH_COOKIES");
  }

  @Test
  void scheduledScanSkipsSessionsThatAreNotDueYet() {
    storedConfig.setNextRotateAt(LocalDateTime.now().plusMinutes(5));

    assertEquals(0, cookieSessionService.refreshDueSessions());
    verify(youtubeCookieRotator, never()).rotate(anyString(), anyLong());
  }

  @Test
  void scheduledScanSkipsSessionsWithAutoRefreshDisabled() {
    storedConfig.setAutoRefreshEnabled(false);
    storedConfig.setNextRotateAt(null);

    assertEquals(0, cookieSessionService.refreshDueSessions());
    verify(youtubeCookieRotator, never()).rotate(anyString(), anyLong());
  }

  @Test
  void scheduledScanRotatesASessionThatHasNeverBeenRefreshed() {
    storedConfig.setNextRotateAt(null);
    when(youtubeCookieRotator.rotate(anyString(), anyLong())).thenReturn(
        new YoutubeCookieRotator.RotationResponse(200,
            List.of(rotatedCookie("__Secure-1PSIDTS", "fresh-1psidts")), 600));

    assertEquals(1, cookieSessionService.refreshDueSessions());
    verify(youtubeCookieRotator, times(1)).rotate(anyString(), anyLong());
  }

  @Test
  void ytDlpInvalidationSignalIsRecordedAndNotifiedOnce() {
    cookieSessionService.markInvalidatedByYtDlp(CookiePlatform.YOUTUBE);
    cookieSessionService.markInvalidatedByYtDlp(CookiePlatform.YOUTUBE);

    assertEquals(CookieSessionStatus.INVALID.name(), storedConfig.getSessionStatus());
    assertEquals("YTDLP_COOKIES_ROTATED", storedConfig.getLastFailureReason());
    verify(cookieSessionNotifyService, times(1))
        .notifySessionInvalidated(CookiePlatform.YOUTUBE, "YTDLP_COOKIES_ROTATED");
  }

  @Test
  void enablingAutoRefreshMakesTheSessionDueImmediately() {
    storedConfig.setAutoRefreshEnabled(false);
    storedConfig.setNextRotateAt(LocalDateTime.now().plusHours(1));

    cookieSessionService.setAutoRefreshEnabled(CookiePlatform.YOUTUBE, true);

    assertTrue(storedConfig.getAutoRefreshEnabled());
    assertEquals(null, storedConfig.getNextRotateAt());
  }

  private NetscapeCookie rotatedCookie(String name, String value) {
    return new NetscapeCookie(".youtube.com", true, "/", true,
        LocalDateTime.now().plusYears(1).toEpochSecond(ZoneOffset.UTC), name, value, false);
  }
}
