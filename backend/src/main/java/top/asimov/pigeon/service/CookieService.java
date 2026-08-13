package top.asimov.pigeon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.locks.Lock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import top.asimov.pigeon.config.StorageProperties;
import top.asimov.pigeon.exception.BusinessException;
import top.asimov.pigeon.helper.CookieSessionLocks;
import top.asimov.pigeon.mapper.CookieConfigMapper;
import top.asimov.pigeon.model.entity.CookieConfig;
import top.asimov.pigeon.model.enums.CookiePlatform;
import top.asimov.pigeon.model.enums.CookieSessionStatus;
import top.asimov.pigeon.model.response.CookieSummaryResponse;
import top.asimov.pigeon.util.NetscapeCookieFile;

@Slf4j
@Service
public class CookieService {

  private static final int MAX_COOKIE_CONTENT_LENGTH = 1_000_000;
  private static final List<CookiePlatform> MANAGED_PLATFORMS = List.of(
      CookiePlatform.YOUTUBE
  );

  public static final String SOURCE_TYPE_UPLOAD = "UPLOAD";
  public static final String SOURCE_TYPE_ROTATED = "ROTATED";
  public static final String SOURCE_TYPE_YTDLP_WRITEBACK = "YTDLP_WRITEBACK";

  private final CookieConfigMapper cookieConfigMapper;
  private final StorageProperties storageProperties;
  private final CookieSessionLocks cookieSessionLocks;

  public CookieService(CookieConfigMapper cookieConfigMapper,
      StorageProperties storageProperties, CookieSessionLocks cookieSessionLocks) {
    this.cookieConfigMapper = cookieConfigMapper;
    this.storageProperties = storageProperties;
    this.cookieSessionLocks = cookieSessionLocks;
  }

  @Transactional(readOnly = true)
  public List<CookieSummaryResponse> listSummaries() {
    return cookieConfigMapper.selectList(new LambdaQueryWrapper<CookieConfig>()
            .in(CookieConfig::getPlatform, MANAGED_PLATFORMS.stream().map(Enum::name).toList()))
        .stream()
        .filter(config -> StringUtils.hasText(config.getPlatform()))
        .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
        .map(this::toSummary)
        .toList();
  }

  @Transactional
  public void upsert(CookiePlatform platform, String cookiesContent) {
    validateManagedPlatform(platform);
    String normalizedContent = normalizeCookiesContent(cookiesContent);
    validateCookiesContent(platform, normalizedContent);

    Lock writeLock = cookieSessionLocks.contentLock(platform).writeLock();
    writeLock.lock();
    try {
      CookieConfig existing = getCookieConfig(platform);
      LocalDateTime now = LocalDateTime.now();
      if (existing == null) {
        CookieConfig created = CookieConfig.builder()
            .platform(platform.name())
            .cookiesContent(normalizedContent)
            .enabled(true)
            .sourceType(SOURCE_TYPE_UPLOAD)
            .createdAt(now)
            .updatedAt(now)
            .sessionStatus(CookieSessionStatus.UNKNOWN.name())
            .autoRefreshEnabled(true)
            .rotateIntervalSeconds(600)
            .rotateFailureCount(0)
            .build();
        cookieConfigMapper.insert(created);
        log.info("[cookie-session] cookies uploaded: platform={} mode=created", platform);
        return;
      }

      existing.setCookiesContent(normalizedContent);
      existing.setEnabled(true);
      existing.setSourceType(SOURCE_TYPE_UPLOAD);
      existing.setUpdatedAt(now);
      // A fresh upload is a brand new session: forget every previous verdict so the next scan
      // probes it immediately instead of inheriting an INVALID state from the old cookies.
      existing.setSessionStatus(CookieSessionStatus.UNKNOWN.name());
      existing.setRotateFailureCount(0);
      existing.setLastFailureReason(null);
      existing.setNextRotateAt(null);
      existing.setLastCheckedAt(null);
      cookieConfigMapper.updateById(existing);
      log.info("[cookie-session] cookies uploaded: platform={} mode=replaced", platform);
    } finally {
      writeLock.unlock();
    }
  }

  @Transactional
  public void delete(CookiePlatform platform) {
    validateManagedPlatform(platform);
    Lock writeLock = cookieSessionLocks.contentLock(platform).writeLock();
    writeLock.lock();
    try {
      cookieConfigMapper.delete(new LambdaQueryWrapper<CookieConfig>()
          .eq(CookieConfig::getPlatform, platform.name()));
      log.info("[cookie-session] cookies deleted: platform={}", platform);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Writes the currently stored cookies to a temporary file for a single yt-dlp run.
   *
   * <p>The returned snapshot timestamp is what makes the write-back safe: if the stored cookies
   * changed while the download was running, the write-back is discarded instead of overwriting a
   * newer session.
   */
  public CookieSnapshot createTempCookiesSnapshot(CookiePlatform platform, String userId) {
    if (platform == null) {
      return null;
    }

    Lock readLock = cookieSessionLocks.contentLock(platform).readLock();
    readLock.lock();
    try {
      CookieConfig cookieConfig = getCookieConfig(platform);
      if (cookieConfig == null || !Boolean.TRUE.equals(cookieConfig.getEnabled())
          || !StringUtils.hasText(cookieConfig.getCookiesContent())) {
        return null;
      }

      Path directory = Path.of(storageProperties.getTempDir(), "cookies");
      Files.createDirectories(directory);
      String fileName = "cookies_" + platform.name().toLowerCase() + "_" + userId + "_"
          + System.currentTimeMillis() + ".txt";
      Path filePath = directory.resolve(fileName);
      Files.writeString(filePath, cookieConfig.getCookiesContent(), StandardCharsets.UTF_8);

      // Best-effort local permission tightening.
      filePath.toFile().setReadable(false, false);
      filePath.toFile().setWritable(false, false);
      filePath.toFile().setReadable(true, true);
      filePath.toFile().setWritable(true, true);

      log.debug("[yt-dlp] platform cookies file created: platform={} path={}", platform, filePath);
      return new CookieSnapshot(platform, filePath.toString(), LocalDateTime.now());
    } catch (IOException e) {
      log.error("[yt-dlp] platform cookies file create failed: platform={}", platform, e);
      throw new RuntimeException("Failed to create temporary cookies file", e);
    } finally {
      readLock.unlock();
    }
  }

  public void deleteTempCookiesFile(String filePath) {
    if (!StringUtils.hasText(filePath)) {
      return;
    }
    try {
      Files.deleteIfExists(Path.of(filePath));
      log.debug("[yt-dlp] platform cookies file deleted: path={}", filePath);
    } catch (IOException e) {
      log.warn("[yt-dlp] platform cookies file delete failed: path={}", filePath, e);
    }
  }

  /**
   * Merges the cookie jar yt-dlp dumped back into the temporary file.
   *
   * <p>yt-dlp rewrites the whole file: it drops the {@code #HttpOnly_} prefixes, replaces the
   * header, adds cookies of its own and removes any cookie the server cleared. Replacing the
   * stored content with that dump would let a single rate-limited request delete the long-lived
   * credentials, so only the freshness cookies are copied over.
   */
  public void mergeYtDlpWriteBack(CookieSnapshot snapshot) {
    if (snapshot == null || !StringUtils.hasText(snapshot.filePath())) {
      return;
    }
    Path filePath = Path.of(snapshot.filePath());
    if (!Files.exists(filePath)) {
      return;
    }

    Lock writeLock = cookieSessionLocks.contentLock(snapshot.platform()).writeLock();
    writeLock.lock();
    try {
      CookieConfig config = getCookieConfig(snapshot.platform());
      if (config == null || !StringUtils.hasText(config.getCookiesContent())) {
        return;
      }
      if (config.getUpdatedAt() != null && config.getUpdatedAt().isAfter(snapshot.takenAt())) {
        log.debug("[cookie-session] write-back discarded: platform={} reason=storeIsNewer",
            snapshot.platform());
        return;
      }

      NetscapeCookieFile stored = NetscapeCookieFile.parse(config.getCookiesContent());
      NetscapeCookieFile dumped = NetscapeCookieFile.parse(
          Files.readString(filePath, StandardCharsets.UTF_8));
      NetscapeCookieFile.MergeResult mergeResult = stored.merge(dumped.cookies(),
          NetscapeCookieFile.rotatableCookieNames(), LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));
      if (!mergeResult.changed()) {
        return;
      }

      config.setCookiesContent(mergeResult.file().serialize());
      config.setSourceType(SOURCE_TYPE_YTDLP_WRITEBACK);
      config.setUpdatedAt(LocalDateTime.now());
      cookieConfigMapper.updateById(config);
      log.info("[cookie-session] write-back merged: platform={} updatedCookies={}",
          snapshot.platform(), mergeResult.updatedNames());
    } catch (IOException e) {
      log.warn("[cookie-session] write-back merge failed: platform={}", snapshot.platform(), e);
    } finally {
      writeLock.unlock();
    }
  }

  public CookieConfig findConfig(CookiePlatform platform) {
    return getCookieConfig(platform);
  }

  public List<CookiePlatform> managedPlatforms() {
    return MANAGED_PLATFORMS;
  }

  private CookieSummaryResponse toSummary(CookieConfig config) {
    return CookieSummaryResponse.builder()
        .platform(config.getPlatform())
        .updatedAt(config.getUpdatedAt())
        .sourceType(config.getSourceType())
        .sessionStatus(CookieSessionStatus.fromNullable(config.getSessionStatus()).name())
        .autoRefreshEnabled(!Boolean.FALSE.equals(config.getAutoRefreshEnabled()))
        .lastRotatedAt(config.getLastRotatedAt())
        .nextRotateAt(config.getNextRotateAt())
        .lastCheckedAt(config.getLastCheckedAt())
        .rotateFailureCount(config.getRotateFailureCount() == null
            ? 0 : config.getRotateFailureCount())
        .lastFailureReason(config.getLastFailureReason())
        .build();
  }

  private CookieConfig getCookieConfig(CookiePlatform platform) {
    if (platform == null) {
      return null;
    }
    return cookieConfigMapper.selectOne(new LambdaQueryWrapper<CookieConfig>()
        .eq(CookieConfig::getPlatform, platform.name())
        .last("LIMIT 1"));
  }

  private void validateManagedPlatform(CookiePlatform platform) {
    if (platform == null || !MANAGED_PLATFORMS.contains(platform)) {
      throw new BusinessException("unsupported cookie platform: " + platform);
    }
  }

  private String normalizeCookiesContent(String cookiesContent) {
    if (!StringUtils.hasText(cookiesContent)) {
      throw new BusinessException("cookies content is required");
    }
    String normalized = cookiesContent.strip();
    if (!StringUtils.hasText(normalized)) {
      throw new BusinessException("cookies content is required");
    }
    if (normalized.length() > MAX_COOKIE_CONTENT_LENGTH) {
      throw new BusinessException("cookies content is too large");
    }
    return normalized;
  }

  private void validateCookiesContent(CookiePlatform platform, String cookiesContent) {
    String lowered = cookiesContent.toLowerCase();
    if (!lowered.contains("# http cookie file") && !lowered.contains("# netscape http cookie file")) {
      throw new BusinessException("cookies file must be in Netscape format");
    }

    if (platform == CookiePlatform.YOUTUBE && !lowered.contains("youtube.com")) {
      throw new BusinessException("cookies file does not appear to be for youtube.com");
    }
  }

  /**
   * A cookie jar handed to one yt-dlp run, together with the moment it was read from the store.
   */
  public record CookieSnapshot(CookiePlatform platform, String filePath, LocalDateTime takenAt) {

  }
}
