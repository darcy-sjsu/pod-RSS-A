package top.asimov.pigeon.util;

import org.springframework.util.StringUtils;
import top.asimov.pigeon.model.constant.Youtube;

public final class FeedSourceUrlBuilder {

  private FeedSourceUrlBuilder() {
  }

  public static String buildEpisodeUrl(String source, String episodeId) {
    if (!StringUtils.hasText(episodeId)) {
      return "";
    }
    return Youtube.VIDEO_URL + episodeId;
  }

  public static String buildChannelUrl(String source, String channelId) {
    return Youtube.CHANNEL_URL + channelId;
  }

  public static String buildPlaylistUrl(String source, String playlistId, String ownerId) {
    return Youtube.PLAYLIST_URL + playlistId;
  }
}
