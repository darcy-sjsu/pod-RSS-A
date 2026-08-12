package top.asimov.pigeon.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.asimov.pigeon.mapper.YoutubeApiDailyUsageMapper;
import top.asimov.pigeon.mapper.YoutubeApiDailyUsageMethodMapper;
import top.asimov.pigeon.model.entity.YoutubeApiDailyUsage;

@ExtendWith(MockitoExtension.class)
class YoutubeQuotaServiceTest {

  @Mock
  private YoutubeApiDailyUsageMapper dailyUsageMapper;
  @Mock
  private YoutubeApiDailyUsageMethodMapper methodMapper;
  @Mock
  private SystemConfigService systemConfigService;
  @InjectMocks
  private YoutubeQuotaService youtubeQuotaService;

  @Test
  void reportsRemoteBlockAsWarningBelowConfiguredThreshold() {
    YoutubeApiDailyUsage usage = YoutubeApiDailyUsage.builder()
        .requestCount(1)
        .quotaUnits(1)
        .autoSyncBlocked(1)
        .blockedReason("REMOTE_QUOTA_EXCEEDED")
        .build();
    when(dailyUsageMapper.selectByDate(anyString())).thenReturn(usage);
    when(methodMapper.selectByDate(anyString())).thenReturn(List.of());
    when(systemConfigService.getYoutubeDailyLimitUnits()).thenReturn(10_000);

    assertTrue(youtubeQuotaService.getTodayUsage().getWarningReached());
  }

  @Test
  void clearsCurrentDayBlock() {
    youtubeQuotaService.clearAutoSyncBlockToday();

    verify(dailyUsageMapper).ensureDayRow(anyString());
    verify(dailyUsageMapper).clearAutoSyncBlock(anyString());
  }
}
