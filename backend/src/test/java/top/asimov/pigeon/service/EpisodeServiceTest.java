package top.asimov.pigeon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import top.asimov.pigeon.config.DownloadProperties;
import top.asimov.pigeon.config.StorageProperties;
import top.asimov.pigeon.mapper.ChannelMapper;
import top.asimov.pigeon.mapper.EpisodeMapper;
import top.asimov.pigeon.mapper.PlaylistEpisodeMapper;
import top.asimov.pigeon.mapper.PlaylistMapper;
import top.asimov.pigeon.model.entity.Episode;
import top.asimov.pigeon.service.storage.S3StorageService;

@ExtendWith(MockitoExtension.class)
class EpisodeServiceTest {

  @Mock
  private EpisodeMapper episodeMapper;
  @Mock
  private ApplicationEventPublisher eventPublisher;
  @Mock
  private MessageSource messageSource;
  @Mock
  private ChannelMapper channelMapper;
  @Mock
  private PlaylistEpisodeMapper playlistEpisodeMapper;
  @Mock
  private PlaylistMapper playlistMapper;
  @Mock
  private StorageProperties storageProperties;
  @Mock
  private S3StorageService s3StorageService;
  @Mock
  private DownloadProperties downloadProperties;

  private EpisodeService episodeService;

  @BeforeEach
  void setUp() {
    episodeService = new EpisodeService(
        episodeMapper,
        eventPublisher,
        messageSource,
        channelMapper,
        playlistEpisodeMapper,
        playlistMapper,
        storageProperties,
        s3StorageService,
        downloadProperties);
  }

  @Test
  void preservesPlaylistReferencesWhenDetachingChannelEpisodes() {
    Episode sharedEpisode = Episode.builder().id("shared").channelId("channel").build();
    Episode orphanEpisode = Episode.builder().id("orphan").channelId("channel").build();
    when(episodeMapper.selectList(any())).thenReturn(List.of(sharedEpisode, orphanEpisode));
    when(playlistEpisodeMapper.countByEpisodeId("shared")).thenReturn(1);
    when(playlistEpisodeMapper.countByEpisodeId("orphan")).thenReturn(0);
    when(episodeMapper.clearChannelId("shared", "channel")).thenReturn(1);
    when(episodeMapper.deleteById("orphan")).thenReturn(1);

    EpisodeService.ChannelEpisodeDetachResult result =
        episodeService.detachChannelEpisodes("channel");

    assertEquals(1, result.detachedCount());
    assertEquals(1, result.deletedCount());
    verify(episodeMapper).clearChannelId("shared", "channel");
    verify(episodeMapper).deleteById("orphan");
    verify(episodeMapper, never()).deleteById("shared");
  }
}
