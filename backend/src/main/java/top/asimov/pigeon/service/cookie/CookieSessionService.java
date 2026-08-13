package top.asimov.pigeon.service.cookie;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.asimov.pigeon.config.CookieRefreshProperties;
import top.asimov.pigeon.exception.BusinessException;
import top.asimov.pigeon.helper.CookieSessionLocks;
import top.asimov.pigeon.mapper.CookieConfigMapper;
import top.asimov.pigeon.model.entity.CookieConfig;
import top.asimov.pigeon.model.enums.CookiePlatform;
import top.asimov.pigeon.model.enums.CookieSessionStatus;
import top.asimov.pigeon.model.response.CookieRotationResponse;
import top.asimov.pigeon.model.response.CookieVerificationResponse;
import top.asimov.pigeon.service.CookieService;
import top.asimov.pigeon.service.YtDlpProxyService;
import top.asimov.pigeon.service.YtDlpRuntimeService;
import top.asimov.pigeon.service.notification.CookieSessionNotifyService;
import top.asimov.pigeon.util.NetscapeCookieFile;

/**
 * Owns the lifecycle of a stored platform cookie session.
 *
 * <p>A YouTube session only stays usable while somebody keeps refreshing it. Letting a browser do
 * that in parallel with yt-dlp makes the two fight over the same credentials, so this service is
 * the single owner: it refreshes on the interval Google declares, records the verdict, and lets
 * downloads read the result.
 */
@Slf4j
@Service
public class CookieSessionService {

  private static final String YTDLP_COOKIE_INVALIDATION_MARKER =
      "The provided YouTube account cookies are no longer valid";
  private static final int RATE_LIMITED_BACKOFF_SECONDS = 300;
  private static final int MAX_BACKOFF_SECONDS = 3600;

  private final CookieConfigMapper cookieConfigMapper;
  private final CookieService cookieService;
  private final CookieSessionLocks cookieSessionLocks;
  private final CookieRefreshProperties properties;
  private final YoutubeCookieRotator youtubeCookieRotator;
  private final CookieSessionNotifyService cookieSessionNotifyService;
  private final YtDlpRuntimeService ytDlpRuntimeService;
  private final YtDlpProxyService ytDlpProxyService;

  public CookieSessionService(CookieConfigMapper cookieConfigMapper, CookieService cookieService,
      CookieSessionLocks cookieSessionLocks, CookieRefreshProperties properties,
      YoutubeCookieRotator youtubeCookieRotator,
      CookieSessionNotifyService cookieSessionNotifyService,
      YtDlpRuntimeService ytDlpRuntimeService, YtDlpProxyService ytDlpProxyService) {
    this.cookieConfigMapper = cookieConfigMapper;
    this.cookieService = cookieService;
    this.cookieSessionLocks = cookieSessionLocks;
    this.properties = properties;
    this.youtubeCookieRotator = youtubeCookieRotator;
    this.cookieSessionNotifyService = cookieSessionNotifyService;
    this.ytDlpRuntimeService = ytDlpRuntimeService;
    this.ytDlpProxyService = ytDlpProxyService;
  }

  /**
   * Refreshes every session whose next refresh is due. Called by the scheduler.
   */
  public int refreshDueSessions() {
    if (!properties.isEnabled()) {
      return 0;
    }
    int refreshed = 0;
    for (CookiePlatform platform : cookieService.managedPlatforms()) {
      CookieConfig config = cookieService.findConfig(platform);
      if (!isDue(config)) {
        continue;
      }
      CookieRotationResponse response = rotate(platform, false);
      if (Outcome.ROTATED.name().equals(response.getOutcome())) {
        refreshed++;
      }
    }
    return refreshed;
  }

  public CookieRotationResponse rotate(CookiePlatform platform, boolean force) {
    requireManagedPlatform(platform);

    ReentrantLock rotationLock = cookieSessionLocks.rotationLock(platform);
    if (!rotationLock.tryLock()) {
      return skipped(platform, Outcome.SKIPPED, "ROTATION_IN_PROGRESS", null);
    }
    try {
      CookieConfig config = cookieService.findConfig(platform);
      if (config == null || !StringUtils.hasText(config.getCookiesContent())) {
        return skipped(platform, Outcome.SKIPPED, "NO_COOKIES_CONFIGURED", null);
      }
      if (!force && Boolean.FALSE.equals(config.getAutoRefreshEnabled())) {
        return skipped(platform, Outcome.SKIPPED, "AUTO_REFRESH_DISABLED", config);
      }
      if (!force && !isDue(config)) {
        return skipped(platform, Outcome.SKIPPED, "NOT_DUE", config);
      }

      NetscapeCookieFile jar = NetscapeCookieFile.parse(config.getCookiesContent());
      if (!jar.hasYoutubeAuthCookies()) {
        applyInvalid(platform, "MISSING_AUTH_COOKIES");
        return skipped(platform, Outcome.FAILED, "MISSING_AUTH_COOKIES",
            cookieService.findConfig(platform));
      }

      String cookieHeader = jar.toCookieHeader(NetscapeCookieFile.rotationRequestCookieNames());
      if (!StringUtils.hasText(cookieHeader)) {
        applyFailure(platform, "MISSING_ROTATION_COOKIES", false);
        return skipped(platform, Outcome.FAILED, "MISSING_ROTATION_COOKIES",
            cookieService.findConfig(platform));
      }

      return executeRotation(platform, jar, cookieHeader);
    } finally {
      rotationLock.unlock();
    }
  }

  private CookieRotationResponse executeRotation(CookiePlatform platform, NetscapeCookieFile jar,
      String cookieHeader) {
    long startedAt = System.currentTimeMillis();
    long nowEpochSeconds = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);

    YoutubeCookieRotator.RotationResponse response;
    try {
      response = youtubeCookieRotator.rotate(cookieHeader, nowEpochSeconds);
    } catch (Exception e) {
      applyFailure(platform, "REQUEST_FAILED", false);
      log.warn("[cookie-session] rotate failed: platform={} reason=requestFailed", platform, e);
      return skipped(platform, Outcome.FAILED, "REQUEST_FAILED", cookieService.findConfig(platform));
    }

    int statusCode = response.statusCode();
    if (statusCode == 429) {
      applyRateLimited(platform);
      log.warn("[cookie-session] rotate rate limited: platform={} statusCode={}", platform,
          statusCode);
      return diagnostic(platform, Outcome.FAILED, "RATE_LIMITED", statusCode, List.of(), List.of(),
          null, cookieService.findConfig(platform));
    }
    if (statusCode == 401 || statusCode == 403) {
      applyFailure(platform, "HTTP_" + statusCode, true);
      log.warn("[cookie-session] rotate rejected: platform={} statusCode={}", platform, statusCode);
      return diagnostic(platform, Outcome.FAILED, "HTTP_" + statusCode, statusCode, List.of(),
          List.of(), null, cookieService.findConfig(platform));
    }
    if (statusCode < 200 || statusCode >= 300) {
      applyFailure(platform, "HTTP_" + statusCode, false);
      log.warn("[cookie-session] rotate failed: platform={} statusCode={}", platform, statusCode);
      return diagnostic(platform, Outcome.FAILED, "HTTP_" + statusCode, statusCode, List.of(),
          List.of(), null, cookieService.findConfig(platform));
    }

    NetscapeCookieFile.MergeResult mergeResult = jar.merge(response.setCookies(),
        NetscapeCookieFile.rotatableCookieNames(), nowEpochSeconds);
    if (!mergeResult.changed()) {
      applyFailure(platform, "NO_COOKIE_RETURNED", false);
      log.warn("[cookie-session] rotate returned no usable cookie: platform={} statusCode={} rejected={}",
          platform, statusCode, mergeResult.rejectedNames());
      return diagnostic(platform, Outcome.FAILED, "NO_COOKIE_RETURNED", statusCode, List.of(),
          List.of(), response.nextIntervalSeconds(), cookieService.findConfig(platform));
    }

    int intervalSeconds = properties.clampIntervalSeconds(response.nextIntervalSeconds());
    CookieConfig persisted = applySuccess(platform, mergeResult.file().serialize(), intervalSeconds);
    long elapsedMs = System.currentTimeMillis() - startedAt;
    log.info("[cookie-session] rotate completed: platform={} statusCode={} rotatedCount={} nextRotateAt={} elapsedMs={}",
        platform, statusCode, mergeResult.updatedNames().size(),
        persisted == null ? null : persisted.getNextRotateAt(), elapsedMs);
    return diagnostic(platform, Outcome.ROTATED, null, statusCode, mergeResult.updatedNames(),
        mergeResult.updatedDomains(), intervalSeconds, persisted);
  }

  public void setAutoRefreshEnabled(CookiePlatform platform, boolean enabled) {
    requireManagedPlatform(platform);
    updateConfig(platform, config -> {
      config.setAutoRefreshEnabled(enabled);
      if (enabled) {
        // Re-enabling should take effect on the next scan instead of waiting out a stale schedule.
        config.setNextRotateAt(null);
      }
    });
    log.info("[cookie-session] auto refresh updated: platform={} enabled={}", platform, enabled);
  }

  /**
   * Records the authoritative invalidation signal emitted by yt-dlp itself.
   */
  public void markInvalidatedByYtDlp(CookiePlatform platform) {
    if (platform == null) {
      return;
    }
    applyInvalid(platform, "YTDLP_COOKIES_ROTATED");
  }

  /**
   * Runs a throwaway yt-dlp extraction with the stored cookies and reports whether YouTube still
   * treats the session as signed in.
   */
  public CookieVerificationResponse verify(CookiePlatform platform) {
    requireManagedPlatform(platform);
    CookieService.CookieSnapshot snapshot = cookieService.createTempCookiesSnapshot(platform, "0");
    if (snapshot == null) {
      throw new BusinessException("no cookies configured for platform " + platform);
    }

    try {
      YtDlpRuntimeService.YtDlpExecutionContext executionContext =
          ytDlpRuntimeService.resolveExecutionContext();
      List<String> command = new ArrayList<>(executionContext.command());
      command.add("--ignore-config");
      command.add("--skip-download");
      command.add("--simulate");
      command.add("--socket-timeout");
      command.add("15");
      command.add("--cookies");
      command.add(snapshot.filePath());
      ytDlpProxyService.appendCurrentProxyArgs(command);
      command.add("--print");
      command.add("id");
      command.add("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

      log.info("[cookie-session] verify started: platform={} command={}", platform,
          ytDlpProxyService.redactCommand(command));
      YtDlpRuntimeService.CommandResult result = ytDlpRuntimeService.runDiagnosticCommand(command,
          executionContext.environment(), properties.getVerifyTimeoutSeconds());
      String output = result.output() == null ? "" : result.output();
      boolean invalidated = output.contains(YTDLP_COOKIE_INVALIDATION_MARKER);

      if (invalidated) {
        applyInvalid(platform, "YTDLP_COOKIES_ROTATED");
        log.warn("[cookie-session] verify failed: platform={} reason=cookiesRotated", platform);
        return CookieVerificationResponse.builder()
            .platform(platform.name())
            .authenticated(false)
            .sessionStatus(CookieSessionStatus.INVALID.name())
            .message("yt-dlp reported the account cookies are no longer valid")
            .build();
      }
      if (result.exitCode() != 0) {
        log.warn("[cookie-session] verify inconclusive: platform={} exitCode={}", platform,
            result.exitCode());
        return CookieVerificationResponse.builder()
            .platform(platform.name())
            .authenticated(false)
            .sessionStatus(currentStatus(platform).name())
            .message("yt-dlp exited with code " + result.exitCode())
            .build();
      }

      applySessionStatus(platform, CookieSessionStatus.ACTIVE, null);
      log.info("[cookie-session] verify completed: platform={} authenticated=true", platform);
      return CookieVerificationResponse.builder()
          .platform(platform.name())
          .authenticated(true)
          .sessionStatus(CookieSessionStatus.ACTIVE.name())
          .message("yt-dlp accepted the stored cookies")
          .build();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.warn("[cookie-session] verify failed: platform={}", platform, e);
      throw new BusinessException("cookie verification failed: " + e.getMessage());
    } finally {
      cookieService.mergeYtDlpWriteBack(snapshot);
      cookieService.deleteTempCookiesFile(snapshot.filePath());
    }
  }

  private boolean isDue(CookieConfig config) {
    if (config == null || !StringUtils.hasText(config.getCookiesContent())) {
      return false;
    }
    if (Boolean.FALSE.equals(config.getAutoRefreshEnabled())) {
      return false;
    }
    return config.getNextRotateAt() == null || !config.getNextRotateAt().isAfter(LocalDateTime.now());
  }

  private CookieConfig applySuccess(CookiePlatform platform, String cookiesContent,
      int intervalSeconds) {
    return updateConfig(platform, config -> {
      LocalDateTime now = LocalDateTime.now();
      config.setCookiesContent(cookiesContent);
      config.setSourceType(CookieService.SOURCE_TYPE_ROTATED);
      config.setUpdatedAt(now);
      config.setSessionStatus(CookieSessionStatus.ACTIVE.name());
      config.setRotateIntervalSeconds(intervalSeconds);
      config.setLastRotatedAt(now);
      config.setNextRotateAt(now.plusSeconds(intervalSeconds));
      config.setLastCheckedAt(now);
      config.setRotateFailureCount(0);
      config.setLastFailureReason(null);
    });
  }

  /**
   * A failed refresh keeps the credentials in place and only downgrades the status. Only a
   * rejection repeated {@code maxConsecutiveFailures} times is treated as a dead session, because
   * a single blocked request must not force the user to sign in again.
   */
  private void applyFailure(CookiePlatform platform, String reason, boolean countsAsRejection) {
    CookieConfig before = cookieService.findConfig(platform);
    int failureCount = (before == null || before.getRotateFailureCount() == null
        ? 0 : before.getRotateFailureCount()) + 1;
    boolean invalid = countsAsRejection && failureCount >= properties.getMaxConsecutiveFailures();
    boolean wasInvalid = before != null
        && CookieSessionStatus.INVALID.name().equals(before.getSessionStatus());

    int backoffSeconds = Math.min(properties.getMinIntervalSeconds() * (1 << Math.min(failureCount, 5)),
        MAX_BACKOFF_SECONDS);
    updateConfig(platform, config -> {
      LocalDateTime now = LocalDateTime.now();
      config.setSessionStatus(
          (invalid ? CookieSessionStatus.INVALID : CookieSessionStatus.STALE).name());
      config.setRotateFailureCount(failureCount);
      config.setLastFailureReason(reason);
      config.setLastCheckedAt(now);
      config.setNextRotateAt(now.plusSeconds(backoffSeconds));
    });

    if (invalid && !wasInvalid) {
      notifyInvalidated(platform, reason);
    }
  }

  private void applyRateLimited(CookiePlatform platform) {
    updateConfig(platform, config -> {
      LocalDateTime now = LocalDateTime.now();
      config.setSessionStatus(CookieSessionStatus.STALE.name());
      config.setLastFailureReason("RATE_LIMITED");
      config.setLastCheckedAt(now);
      config.setNextRotateAt(now.plusSeconds(RATE_LIMITED_BACKOFF_SECONDS));
    });
  }

  private void applyInvalid(CookiePlatform platform, String reason) {
    CookieConfig before = cookieService.findConfig(platform);
    boolean wasInvalid = before != null
        && CookieSessionStatus.INVALID.name().equals(before.getSessionStatus());
    applySessionStatus(platform, CookieSessionStatus.INVALID, reason);
    if (!wasInvalid) {
      log.warn("[cookie-session] session invalidated: platform={} reason={}", platform, reason);
      notifyInvalidated(platform, reason);
    }
  }

  private void applySessionStatus(CookiePlatform platform, CookieSessionStatus status,
      String reason) {
    updateConfig(platform, config -> {
      config.setSessionStatus(status.name());
      config.setLastFailureReason(reason);
      config.setLastCheckedAt(LocalDateTime.now());
    });
  }

  private void notifyInvalidated(CookiePlatform platform, String reason) {
    try {
      cookieSessionNotifyService.notifySessionInvalidated(platform, reason);
    } catch (Exception e) {
      log.warn("[cookie-session] invalidation notification failed: platform={}", platform, e);
    }
  }

  private CookieSessionStatus currentStatus(CookiePlatform platform) {
    CookieConfig config = cookieService.findConfig(platform);
    return CookieSessionStatus.fromNullable(config == null ? null : config.getSessionStatus());
  }

  private CookieConfig updateConfig(CookiePlatform platform, java.util.function.Consumer<CookieConfig> mutator) {
    Lock writeLock = cookieSessionLocks.contentLock(platform).writeLock();
    writeLock.lock();
    try {
      CookieConfig config = cookieConfigMapper.selectOne(new LambdaQueryWrapper<CookieConfig>()
          .eq(CookieConfig::getPlatform, platform.name())
          .last("LIMIT 1"));
      if (config == null) {
        return null;
      }
      mutator.accept(config);
      cookieConfigMapper.updateById(config);
      return config;
    } finally {
      writeLock.unlock();
    }
  }

  private void requireManagedPlatform(CookiePlatform platform) {
    if (platform == null || !cookieService.managedPlatforms().contains(platform)) {
      throw new BusinessException("unsupported cookie platform: " + platform);
    }
  }

  private CookieRotationResponse skipped(CookiePlatform platform, Outcome outcome, String reason,
      CookieConfig config) {
    return diagnostic(platform, outcome, reason, null, List.of(), List.of(), null, config);
  }

  private CookieRotationResponse diagnostic(CookiePlatform platform, Outcome outcome, String reason,
      Integer statusCode, List<String> rotatedNames, List<String> rotatedDomains,
      Integer nextIntervalSeconds, CookieConfig config) {
    return CookieRotationResponse.builder()
        .platform(platform.name())
        .outcome(outcome.name())
        .reason(reason)
        .statusCode(statusCode)
        .sessionStatus(CookieSessionStatus.fromNullable(
            config == null ? null : config.getSessionStatus()).name())
        .rotatedCookieNames(rotatedNames)
        .rotatedCookieDomains(rotatedDomains)
        .nextIntervalSeconds(nextIntervalSeconds)
        .nextRotateAt(config == null ? null : config.getNextRotateAt())
        .build();
  }

  private enum Outcome {
    ROTATED,
    SKIPPED,
    FAILED
  }
}
