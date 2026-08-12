package top.asimov.pigeon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import top.asimov.pigeon.config.AppBaseUrlResolver;
import top.asimov.pigeon.helper.YoutubeChannelHelper;
import top.asimov.pigeon.helper.YoutubeHelper;
import top.asimov.pigeon.mapper.ChannelMapper;
import top.asimov.pigeon.model.entity.Channel;
import top.asimov.pigeon.model.enums.FeedSource;

@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

  @Mock
  private ChannelMapper channelMapper;
  @Mock
  private EpisodeService episodeService;
  @Mock
  private ApplicationEventPublisher eventPublisher;
  @Mock
  private YoutubeHelper youtubeHelper;
  @Mock
  private YoutubeChannelHelper youtubeChannelHelper;
  @Mock
  private AccountService accountService;
  @Mock
  private MessageSource messageSource;
  @Mock
  private FeedDefaultsService feedDefaultsService;
  @Mock
  private AppBaseUrlResolver appBaseUrlResolver;

  private ChannelService channelService;

  @BeforeEach
  void setUp() {
    channelService = new ChannelService(
        channelMapper,
        episodeService,
        eventPublisher,
        youtubeHelper,
        youtubeChannelHelper,
        accountService,
        messageSource,
        feedDefaultsService,
        appBaseUrlResolver);
    lenient().when(messageSource.getMessage(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void normalizesSourceWhenSavingYoutubeChannel() {
    Channel channel = Channel.builder()
        .id("channel")
        .title("Channel")
        .source("bilibili")
        .autoDownloadEnabled(Boolean.FALSE)
        .build();

    channelService.saveChannel(channel);

    assertEquals(FeedSource.YOUTUBE.name(), channel.getSource());
    verify(channelMapper).insert(channel);
  }

  @Test
  void deletesChannelWithoutDeletingPlaylistOwnedEpisodes() {
    Channel channel = Channel.builder().id("channel").title("Channel").build();
    when(channelMapper.selectById("channel")).thenReturn(channel);
    when(episodeService.detachChannelEpisodes("channel"))
        .thenReturn(new EpisodeService.ChannelEpisodeDetachResult(2, 1));
    when(channelMapper.deleteById("channel")).thenReturn(1);

    channelService.deleteChannel("channel");

    verify(episodeService).detachChannelEpisodes("channel");
    verify(channelMapper).deleteById("channel");
    verify(episodeService, never()).deleteEpisodesByChannelId("channel");
  }
}
