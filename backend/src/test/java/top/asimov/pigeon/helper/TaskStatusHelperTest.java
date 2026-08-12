package top.asimov.pigeon.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.asimov.pigeon.mapper.EpisodeMapper;

@ExtendWith(MockitoExtension.class)
class TaskStatusHelperTest {

  @Mock
  private EpisodeMapper episodeMapper;

  @Test
  void claimsEpisodeOnlyWhenConditionalUpdateSucceeds() {
    TaskStatusHelper helper = new TaskStatusHelper(episodeMapper);
    when(episodeMapper.tryMarkDownloading(eq("episode"), any(LocalDateTime.class)))
        .thenReturn(1, 0);

    assertTrue(helper.tryMarkDownloading("episode"));
    assertFalse(helper.tryMarkDownloading("episode"));

    verify(episodeMapper, org.mockito.Mockito.times(2))
        .tryMarkDownloading(eq("episode"), any(LocalDateTime.class));
  }
}
