package top.asimov.pigeon.scheduler;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.asimov.pigeon.service.cookie.CookieSessionService;

/**
 * Keeps stored cookie sessions fresh.
 *
 * <p>Scanning every minute is deliberate: Google declares a ten minute rotation interval, and a
 * one minute granularity both tracks that comfortably and stays above the endpoint's rate limit.
 */
@Slf4j
@Component
public class CookieSessionScheduler {

  private final CookieSessionService cookieSessionService;

  public CookieSessionScheduler(CookieSessionService cookieSessionService) {
    this.cookieSessionService = cookieSessionService;
  }

  @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
  public void refreshDueCookieSessions() {
    try {
      int refreshed = cookieSessionService.refreshDueSessions();
      if (refreshed > 0) {
        log.info("[scheduler] cookie session scan completed: refreshed={}", refreshed);
      }
    } catch (Exception e) {
      log.error("[scheduler] cookie session scan failed", e);
    }
  }
}
