package top.asimov.pigeon.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.asimov.pigeon.model.enums.YoutubeApiCallContext;
import top.asimov.pigeon.model.enums.YoutubeApiMethod;
import top.asimov.pigeon.service.YoutubeQuotaService;

@ExtendWith(MockitoExtension.class)
class YoutubeApiExecutorTest {

  @Mock
  private YoutubeQuotaService youtubeQuotaService;

  @Test
  void retriesTransientIoFailuresAndRecordsEveryAttempt() throws IOException {
    YoutubeApiExecutor executor = new YoutubeApiExecutor(youtubeQuotaService);
    AtomicInteger attempts = new AtomicInteger();
    when(youtubeQuotaService.reserveAndRecord(
        YoutubeApiMethod.VIDEOS_LIST, YoutubeApiCallContext.MANUAL)).thenReturn(true);

    String result = executor.execute(YoutubeApiMethod.VIDEOS_LIST, () -> {
      if (attempts.incrementAndGet() < 3) {
        throw new IOException("temporary network failure");
      }
      return "ok";
    });

    assertEquals("ok", result);
    verify(youtubeQuotaService, times(3)).reserveAndRecord(
        eq(YoutubeApiMethod.VIDEOS_LIST), any(YoutubeApiCallContext.class));
  }
}
