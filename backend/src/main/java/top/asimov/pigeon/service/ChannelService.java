package top.asimov.pigeon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import top.asimov.pigeon.config.AppBaseUrlResolver;
import top.asimov.pigeon.event.DownloadTaskEvent.DownloadTargetType;
import top.asimov.pigeon.exception.BusinessException;
import top.asimov.pigeon.handler.FeedEpisodeHelper;
import top.asimov.pigeon.helper.YoutubeChannelHelper;
import top.asimov.pigeon.helper.YoutubeHelper;
import top.asimov.pigeon.mapper.ChannelMapper;
import top.asimov.pigeon.model.entity.Channel;
import top.asimov.pigeon.model.entity.Episode;
import top.asimov.pigeon.model.enums.FeedSource;
import top.asimov.pigeon.model.response.FeedConfigUpdateResult;
import top.asimov.pigeon.model.response.FeedPack;
import top.asimov.pigeon.model.response.FeedRefreshResult;
import top.asimov.pigeon.model.response.FeedSaveResult;
import top.asimov.pigeon.util.FeedEpisodeVisibilityHelper;
import top.asimov.pigeon.util.FeedSourceUrlBuilder;

@Slf4j
@Service
public class ChannelService extends AbstractFeedService<Channel> {

  private static final String CURSOR_TYPE_YOUTUBE_PAGE_TOKEN = "YOUTUBE_PAGE_TOKEN";

  private final ChannelMapper channelMapper;
  private final YoutubeHelper youtubeHelper;
  private final YoutubeChannelHelper youtubeChannelHelper;
  private final AccountService accountService;
  private final MessageSource messageSource;
  private final AppBaseUrlResolver appBaseUrlResolver;

  public ChannelService(ChannelMapper channelMapper, EpisodeService episodeService,
      ApplicationEventPublisher eventPublisher, YoutubeHelper youtubeHelper,
      YoutubeChannelHelper youtubeChannelHelper,
      AccountService accountService,
      MessageSource messageSource, FeedDefaultsService feedDefaultsService,
      AppBaseUrlResolver appBaseUrlResolver) {
    super(episodeService, eventPublisher, messageSource, feedDefaultsService);
    this.channelMapper = channelMapper;
    this.youtubeHelper = youtubeHelper;
    this.youtubeChannelHelper = youtubeChannelHelper;
    this.accountService = accountService;
    this.messageSource = messageSource;
    this.appBaseUrlResolver = appBaseUrlResolver;
  }

  /**
   * 获取所有频道列表，包含最后上传时间
   *
   * @return 频道列表
   */
  public List<Channel> selectChannelList() {
    return channelMapper.selectChannelsByLastUploadedAt();
  }

  /**
   * 获取频道详情
   *
   * @param id 频道ID
   * @return 频道对象
   */
  public Channel channelDetail(String id) {
    Channel channel = channelMapper.selectById(id);
    if (channel == null) {
      throw new BusinessException(
          messageSource.getMessage("channel.not.found", new Object[]{id},
              LocaleContextHolder.getLocale()));
    }
    channel.setOriginalUrl(FeedSourceUrlBuilder.buildChannelUrl(channel.getSource(), channel.getId()));
    return channel;
  }

  /**
   * 根据频道ID或handler查找频道
   *
   * @param channelIdentification 频道ID或handler
   * @return 频道对象，如果未找到则返回null
   */
  public Channel findChannelByIdentification(String channelIdentification) {
    // 先按ID查询
    Channel channel = channelMapper.selectById(channelIdentification);
    if (channel != null) {
      return channel;
    }
    // 再按handler查询
    LambdaQueryWrapper<Channel> queryWrapper = new LambdaQueryWrapper<>();
    queryWrapper.eq(Channel::getHandler, channelIdentification);
    return channelMapper.selectOne(queryWrapper);
  }

  /**
   * 获取频道的RSS订阅链接
   *
   * @param channelId 频道ID
   * @return RSS订阅链接
   */
  public String getChannelRssFeedUrl(String channelId) {
    Channel channel = channelMapper.selectById(channelId);
    if (ObjectUtils.isEmpty(channel)) {
      throw new BusinessException(
          messageSource.getMessage("channel.not.found", new Object[]{channelId},
              LocaleContextHolder.getLocale()));
    }
    String apiKey = accountService.getApiKey();
    if (ObjectUtils.isEmpty(apiKey)) {
      throw new BusinessException(
          messageSource.getMessage("channel.api.key.failed", null,
              LocaleContextHolder.getLocale()));
    }
    return appBaseUrlResolver.requireBaseUrl() + "/api/rss/" + channelId + ".xml?apikey=" + apiKey;
  }

  /**
   * 更新频道的配置项
   *
   * @param channelId     频道ID
   * @param configuration 包含更新配置的Channel对象
   * @return 更新后的频道对象
   */
  @Transactional
  public FeedConfigUpdateResult updateChannelConfig(String channelId, Channel configuration) {
    FeedConfigUpdateResult result = updateFeedConfig(channelId, configuration);
    log.info("[feed] channel config updated: channelId={}", channelId);
    return result;
  }

  @Transactional
  public void updateChannelCustomCoverExt(String channelId, String customCoverExt) {
    Channel channel = channelMapper.selectById(channelId);
    if (channel == null) {
      throw new BusinessException(
          messageSource.getMessage("channel.not.found", new Object[]{channelId},
              LocaleContextHolder.getLocale()));
    }
    channel.setCustomCoverExt(customCoverExt);
    int updated = channelMapper.updateById(channel);
    if (updated <= 0) {
      throw new BusinessException(messageSource.getMessage("feed.config.update.failed", null,
          LocaleContextHolder.getLocale()));
    }
  }

  /**
   * 根据用户输入的链接，获取频道信息和预览视频列表
   *
   * @param channelUrl 包含用户输入的频道URL或ID等
   * @return 包含频道详情和预览视频列表的FeedPack对象
   */
  public FeedPack<Channel> fetchChannel(String channelUrl) {
    if (ObjectUtils.isEmpty(channelUrl)) {
      throw new BusinessException(
          messageSource.getMessage("channel.source.empty", null, LocaleContextHolder.getLocale()));
    }

    com.google.api.services.youtube.model.Channel ytChannel = youtubeHelper.fetchYoutubeChannel(channelUrl);

    String ytChannelId = ytChannel.getId();
    Channel fetchedChannel = Channel.builder()
        .id(ytChannelId)
        .title(ytChannel.getSnippet().getTitle())
        .coverUrl(ytChannel.getSnippet().getThumbnails().getHigh().getUrl())
        .description(ytChannel.getSnippet().getDescription())
        .subscribedAt(LocalDateTime.now())
        .source(FeedSource.YOUTUBE.name()) // 目前只支持YouTube
        .originalUrl(channelUrl)
        .autoDownloadEnabled(Boolean.TRUE)
        .build();
    feedDefaultsService().applyDefaultsIfMissing(fetchedChannel);

    // 获取一页用于预览，并应用默认过滤配置
    List<Episode> episodes = youtubeChannelHelper.fetchYoutubeChannelVideos(
        ytChannelId,
        1,
        null,
        fetchedChannel.getTitleContainKeywords(),
        fetchedChannel.getTitleExcludeKeywords(),
        fetchedChannel.getDescriptionContainKeywords(),
        fetchedChannel.getDescriptionExcludeKeywords(),
        fetchedChannel.getMinimumDuration(),
        fetchedChannel.getMaximumDuration());
    episodes = FeedEpisodeVisibilityHelper.filterVisibleEpisodes(fetchedChannel, episodes);
    episodes = episodes.size() > DEFAULT_PREVIEW_NUM ? episodes.subList(0, DEFAULT_PREVIEW_NUM) : episodes;
    return FeedPack.<Channel>builder().feed(fetchedChannel).episodes(episodes).build();
  }

  /**
   * 预览频道的最新视频
   *
   * @param channel 包含频道ID和筛选条件的Channel对象
   * @return 预览的视频列表
   */
  public FeedPack<Channel> previewChannel(Channel channel) {
    return previewFeed(channel);
  }

  /**
   * 保存频道并初始化下载最新的视频 当autoDownloadLimit较大时（> ASYNC_FETCH_NUM），使用异步处理模式
   *
   * @param channel 要保存的频道信息
   * @return 包含频道信息和处理状态的FeedSaveResult对象
   */
  @Transactional
  public FeedSaveResult<Channel> saveChannel(Channel channel) {
    channel.setSource(FeedSource.YOUTUBE.name());
    feedDefaultsService().applyDefaultsIfMissing(channel);
    return saveFeed(channel);
  }

  /**
   * 查找所有需要同步的频道
   *
   * @param checkTime 检查时间点，所有 lastSyncTimestamp 早于该时间点的频道都需要同步
   * @return 需要同步的频道列表
   */
  public List<Channel> findDueForSync(LocalDateTime checkTime) {
    List<Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<>());
    return channels.stream()
        .filter(c -> c.getLastSyncTimestamp() == null ||
            c.getLastSyncTimestamp().isBefore(checkTime))
        .collect(Collectors.toList());
  }

  /**
   * 删除频道及其所有关联资源
   *
   * @param channelId 要删除的频道ID
   */
  @Transactional
  public void deleteChannel(String channelId) {
    log.info("[feed] channel delete started: channelId={}", channelId);

    Channel channel = channelMapper.selectById(channelId);
    if (channel == null) {
      throw new BusinessException(
          messageSource.getMessage("channel.not.found", new Object[]{channelId},
              LocaleContextHolder.getLocale()));
    }

    EpisodeService.ChannelEpisodeDetachResult detachResult =
        episodeService().detachChannelEpisodes(channelId);
    int result = channelMapper.deleteById(channelId);
    if (result > 0) {
      log.info(
          "[feed] channel delete completed: channelId={} title={} playlistEpisodesPreserved={} orphanEpisodesDeleted={}",
          channelId, channel.getTitle(), detachResult.detachedCount(), detachResult.deletedCount());
    } else {
      log.error("[feed] channel delete failed: channelId={} title={}", channelId,
          channel.getTitle());
      throw new BusinessException(
          messageSource.getMessage("channel.delete.failed", null, LocaleContextHolder.getLocale()));
    }
  }

  @Transactional
  public FeedRefreshResult refreshChannelById(String channelId) {
    Channel channel = channelMapper.selectById(channelId);
    if (channel == null) {
      throw new BusinessException(
          messageSource.getMessage("channel.not.found", new Object[]{channelId},
              LocaleContextHolder.getLocale()));
    }
    return refreshChannel(channel);
  }

  /**
   * 同步频道，检查是否有新视频并处理
   *
   * @param channel 要同步的频道对象
   */
  @Transactional
  public FeedRefreshResult refreshChannel(Channel channel) {
    log.info("[feed-sync] channel refresh started: channelId={} title={}", channel.getId(),
        channel.getTitle());
    return refreshFeed(channel);
  }

  /**
   * 拉取频道历史节目信息：优先按持久化 cursor 推进；对老订阅首次 history 调用时，
   * 从头扫描到本地最早已保存节目，并同步补齐中间缺失的历史元数据。
   *
   * @param channelId 频道 ID
   * @return 新增的节目信息列表（已去重）
   */
  @Transactional
  public List<Episode> fetchChannelHistory(String channelId) {
    Channel channel = channelMapper.selectById(channelId);
    if (channel == null) {
      throw new BusinessException(
          messageSource.getMessage("channel.not.found", new Object[]{channelId},
              LocaleContextHolder.getLocale()));
    }

    Episode earliestLocalEpisode = episodeService().getEarliestEpisodeByChannelId(channelId);
    if (earliestLocalEpisode == null) {
      log.warn("[feed-sync] channel history fetch skipped: channelId={} reason=noLocalEpisodes",
          channelId);
      return Collections.emptyList();
    }
    if (Boolean.TRUE.equals(channel.getHistoryCursorExhausted())) {
      log.info("[feed-sync] channel history fetch skipped: channelId={} reason=cursorExhausted",
          channelId);
      return Collections.emptyList();
    }

    return hasPersistedHistoryCursor(channel)
        ? fetchYoutubeChannelHistoryByCursor(channel)
        : bootstrapYoutubeChannelHistoryCursor(channel, earliestLocalEpisode);
  }

  /**
   * 获取并保存初始化的视频
   *
   * @param channelId         频道ID
   * @param autoDownloadLimit 要自动下载的节目数量上限
   * @param containKeywords   包含关键词
   * @param excludeKeywords   排除关键词
   * @param minimumDuration   最小时长
   * @param maximumDuration   最长时长
   */
  @Transactional
  public void processChannelInitializationAsync(String channelId, Integer autoDownloadLimit,
      String containKeywords, String excludeKeywords, Integer minimumDuration,
      Integer maximumDuration) {
    log.info("[feed-sync] channel initialization started: channelId={} autoDownloadLimit={}",
        channelId, autoDownloadLimit);

    try {
      Channel channel = channelMapper.selectById(channelId);
      if (channel == null) {
        log.warn("[feed-sync] channel initialization skipped: channelId={} reason=notFound",
            channelId);
        return;
      }
      int downloadLimit = autoDownloadLimit != null && autoDownloadLimit > 0 ? autoDownloadLimit : 0;

      List<Episode> episodes = youtubeChannelHelper.fetchYoutubeChannelVideos(
          channelId, 1, null, containKeywords, excludeKeywords, null, null, minimumDuration,
          maximumDuration);

      if (episodes.isEmpty()) {
        log.info("[feed-sync] channel initialization completed: channelId={} count=0",
            channelId);
        return;
      }

      FeedEpisodeHelper.findLatestEpisode(episodes).ifPresent(latest -> {
        channel.setLastSyncVideoId(latest.getId());
        channel.setLastSyncTimestamp(LocalDateTime.now());
        initializeHistoryCursorAfterInitialFetch(channel, episodes);
        channelMapper.updateById(channel);
      });
      List<Episode> episodesToPersist = prepareEpisodesForPersistence(episodes);
      episodeService().saveEpisodes(episodesToPersist);
      episodeService().backfillChannelIdIfMissing(channelId, episodesToPersist);
      // 入库所有节目（包括仅保存元数据的部分）
      afterEpisodesPersisted(channel, episodesToPersist);

      List<Episode> visibleEpisodes = FeedEpisodeVisibilityHelper.filterVisibleEpisodes(channel, episodesToPersist);
      if (downloadLimit > 0) {
        // 仅对前 downloadLimit 个节目参与自动下载（根据延迟配置决定是否立即入队）
        List<Episode> episodesToDownload = visibleEpisodes;
        if (visibleEpisodes.size() > downloadLimit) {
          episodesToDownload = visibleEpisodes.subList(0, downloadLimit);
        }
        markAndPublishAutoDownloadEpisodes(
            channel,
            episodesToDownload,
            buildEpisodesCreatedContext("init", channel));
      }

      log.info("[feed-sync] channel initialization completed: channelId={} count={}", channelId,
          episodes.size());

    } catch (Exception e) {
      log.error("[feed-sync] channel initialization failed: channelId={} reason={}", channelId,
          e.getMessage(), e);
    }
  }

  @Override
  protected Optional<Channel> findFeedById(String feedId) {
    return Optional.ofNullable(channelMapper.selectById(feedId));
  }

  @Override
  protected int updateFeed(Channel feed) {
    return channelMapper.updateById(feed);
  }

  @Override
  protected void insertFeed(Channel feed) {
    channelMapper.insert(feed);
  }

  @Override
  protected DownloadTargetType downloadTargetType() {
    return DownloadTargetType.CHANNEL;
  }

  @Override
  protected List<Episode> fetchEpisodes(Channel feed) {
    int pages = Math.max(1, (int) Math.ceil((double) Math.max(1, AbstractFeedService.DEFAULT_PREVIEW_NUM) / 50.0));
    List<Episode> episodes = youtubeChannelHelper.fetchYoutubeChannelVideos(
        feed.getId(), pages, null,
        feed.getTitleContainKeywords(), feed.getTitleExcludeKeywords(),
        feed.getDescriptionContainKeywords(), feed.getDescriptionExcludeKeywords(),
        feed.getMinimumDuration(),
        feed.getMaximumDuration());
    episodes = FeedEpisodeVisibilityHelper.filterVisibleEpisodes(feed, episodes);
    if (episodes.size() > AbstractFeedService.DEFAULT_PREVIEW_NUM) {
      episodes = episodes.subList(0, AbstractFeedService.DEFAULT_PREVIEW_NUM);
    }
    return episodes;
  }

  @Override
  protected List<Episode> fetchIncrementalEpisodes(Channel feed) {
    // 仅抓取最新一页（最多 50 条），通过与数据库已有的 Episode ID 做差值，确定真正新增的节目。
    List<Episode> episodes = youtubeChannelHelper.fetchYoutubeChannelVideos(
        feed.getId(),
        1,
        null,
        feed.getTitleContainKeywords(),
        feed.getTitleExcludeKeywords(),
        feed.getDescriptionContainKeywords(),
        feed.getDescriptionExcludeKeywords(),
        feed.getMinimumDuration(),
        feed.getMaximumDuration());

    // 将已存在但 channel_id 为空的节目补回频道归属。
    episodeService().backfillChannelIdIfMissing(feed.getId(), episodes);

    return filterNewEpisodes(episodes);
  }

  private List<Episode> fetchYoutubeChannelHistoryByCursor(Channel channel) {
    YoutubeChannelHelper.PageHistoryResult page =
        youtubeChannelHelper.fetchChannelHistoryPage(channel.getId(), channel.getHistoryCursorValue());
    return persistHistoryPage(channel, page.episodes(), page.nextPageToken(), page.exhausted(),
        CURSOR_TYPE_YOUTUBE_PAGE_TOKEN, nextHistoryPageNumber(channel) + 1);
  }

  private List<Episode> bootstrapYoutubeChannelHistoryCursor(Channel channel, Episode earliestLocalEpisode) {
    String nextPageToken = null;
    int pageNumber = 1;
    List<Episode> visibleNewEpisodes = new java.util.ArrayList<>();

    while (true) {
      YoutubeChannelHelper.PageHistoryResult page =
          youtubeChannelHelper.fetchChannelHistoryPage(channel.getId(), nextPageToken);
      visibleNewEpisodes.addAll(persistBootstrapPage(channel, page.episodes()));
      if (containsEpisode(page.episodes(), earliestLocalEpisode.getId())) {
        updateHistoryCursor(channel, CURSOR_TYPE_YOUTUBE_PAGE_TOKEN, page.nextPageToken(), pageNumber + 1,
            page.exhausted());
        if (!page.exhausted()) {
          visibleNewEpisodes.addAll(fetchYoutubeChannelHistoryByCursor(channel));
        }
        return visibleNewEpisodes;
      }
      if (page.exhausted()) {
        markHistoryExhausted(channel);
        return visibleNewEpisodes;
      }
      nextPageToken = page.nextPageToken();
      pageNumber++;
    }
  }

  private List<Episode> persistBootstrapPage(Channel channel, List<Episode> episodes) {
    if (episodes == null || episodes.isEmpty()) {
      return Collections.emptyList();
    }
    List<Episode> newEpisodes = filterNewEpisodes(episodes);
    if (newEpisodes.isEmpty()) {
      return Collections.emptyList();
    }
    List<Episode> episodesToPersist = prepareEpisodesForPersistence(newEpisodes);
    episodeService().saveEpisodes(episodesToPersist);
    episodeService().backfillChannelIdIfMissing(channel.getId(), episodesToPersist);
    return FeedEpisodeVisibilityHelper.filterVisibleEpisodes(channel, episodesToPersist);
  }

  private List<Episode> persistHistoryPage(Channel channel, List<Episode> episodes, String nextCursorValue,
      boolean exhausted, String cursorType, int nextPageNumber) {
    if (episodes == null || episodes.isEmpty()) {
      if (exhausted) {
        markHistoryExhausted(channel);
      }
      return Collections.emptyList();
    }

    List<Episode> newEpisodes = filterNewEpisodes(episodes);
    List<Episode> episodesToPersist = prepareEpisodesForPersistence(newEpisodes);
    if (!episodesToPersist.isEmpty()) {
      episodeService().saveEpisodes(episodesToPersist);
      episodeService().backfillChannelIdIfMissing(channel.getId(), episodesToPersist);
    }
    updateHistoryCursor(channel, cursorType, nextCursorValue, nextPageNumber, exhausted);
    return FeedEpisodeVisibilityHelper.filterVisibleEpisodes(channel, episodesToPersist);
  }

  private void initializeHistoryCursorAfterInitialFetch(Channel channel, List<Episode> fetchedEpisodes) {
    if (channel == null || fetchedEpisodes == null || fetchedEpisodes.isEmpty()) {
      return;
    }
    YoutubeChannelHelper.PageHistoryResult page =
        youtubeChannelHelper.fetchChannelHistoryPage(channel.getId(), null);
    updateHistoryCursor(channel, CURSOR_TYPE_YOUTUBE_PAGE_TOKEN, page.nextPageToken(), 2, page.exhausted());
  }

  private boolean hasPersistedHistoryCursor(Channel channel) {
    return channel != null
        && (channel.getHistoryCursorPage() != null
        || org.springframework.util.StringUtils.hasText(channel.getHistoryCursorValue()));
  }

  private int nextHistoryPageNumber(Channel channel) {
    return channel != null && channel.getHistoryCursorPage() != null ? channel.getHistoryCursorPage() : 1;
  }

  private void updateHistoryCursor(Channel channel, String type, String value, int nextPageNumber, boolean exhausted) {
    channel.setHistoryCursorType(type);
    channel.setHistoryCursorValue(value);
    channel.setHistoryCursorPage(nextPageNumber);
    channel.setHistoryCursorExhausted(exhausted);
    channel.setHistoryCursorUpdatedAt(LocalDateTime.now());
    channelMapper.updateById(channel);
  }

  private void markHistoryExhausted(Channel channel) {
    updateHistoryCursor(channel, channel.getHistoryCursorType(), null, channel.getHistoryCursorPage(), true);
  }

  private boolean containsEpisode(List<Episode> episodes, String episodeId) {
    if (episodes == null || episodes.isEmpty() || !org.springframework.util.StringUtils.hasText(episodeId)) {
      return false;
    }
    return episodes.stream().anyMatch(episode -> episodeId.equals(episode.getId()));
  }

  @Override
  protected org.slf4j.Logger logger() {
    return log;
  }

}
