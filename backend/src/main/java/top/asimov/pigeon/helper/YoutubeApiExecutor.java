package top.asimov.pigeon.helper;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.asimov.pigeon.exception.YoutubeAutoSyncBlockedException;
import top.asimov.pigeon.model.enums.YoutubeApiCallContext;
import top.asimov.pigeon.model.enums.YoutubeApiMethod;
import top.asimov.pigeon.service.YoutubeQuotaService;

@Slf4j
@Component
public class YoutubeApiExecutor {

  private static final int MAX_ATTEMPTS = 3;
  private static final long INITIAL_RETRY_DELAY_MS = 250L;

  private final YoutubeQuotaService youtubeQuotaService;

  public YoutubeApiExecutor(YoutubeQuotaService youtubeQuotaService) {
    this.youtubeQuotaService = youtubeQuotaService;
  }

  public <T> T execute(YoutubeApiMethod method, YoutubeExecutable<T> executable) throws IOException {
    YoutubeApiCallContext callContext = YoutubeQuotaContextHolder.get();
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      boolean reserved = youtubeQuotaService.reserveAndRecord(method, callContext);
      if (!reserved && callContext == YoutubeApiCallContext.AUTO_SYNC) {
        throw new YoutubeAutoSyncBlockedException(
            "YouTube daily quota reached; auto sync is blocked until the next Pacific day");
      }

      try {
        return executable.execute();
      } catch (GoogleJsonResponseException exception) {
        if (youtubeQuotaService.isQuotaExceededError(exception)) {
          youtubeQuotaService.markAutoSyncBlockedByRemoteQuota();
          log.warn("[youtube-api] remote quota exceeded, auto sync blocked: statusCode={} message={}",
              exception.getStatusCode(),
              exception.getDetails() == null
                  ? exception.getMessage()
                  : exception.getDetails().getMessage());
          throw exception;
        }
        if (!isRetryableStatus(exception.getStatusCode()) || attempt == MAX_ATTEMPTS) {
          throw exception;
        }
        waitBeforeRetry(method, attempt, exception);
      } catch (IOException exception) {
        if (attempt == MAX_ATTEMPTS) {
          throw exception;
        }
        waitBeforeRetry(method, attempt, exception);
      }
    }
    throw new IOException("YouTube API request failed after retries");
  }

  private boolean isRetryableStatus(int statusCode) {
    return statusCode == 429 || statusCode >= 500;
  }

  private void waitBeforeRetry(YoutubeApiMethod method, int attempt, IOException exception)
      throws IOException {
    long delayMs = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1));
    log.warn("[youtube-api] transient request failure, retrying: method={} attempt={} delayMs={} reason={}",
        method.methodName(), attempt, delayMs, exception.getMessage());
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new IOException("YouTube API retry interrupted", interruptedException);
    }
  }

  @FunctionalInterface
  public interface YoutubeExecutable<T> {

    T execute() throws IOException;
  }
}
