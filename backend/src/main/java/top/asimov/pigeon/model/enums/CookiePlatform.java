package top.asimov.pigeon.model.enums;

public enum CookiePlatform {

  YOUTUBE,
  RUMBLE;

  public static CookiePlatform fromFeedSource(String rawSource) {
    if (FeedSource.YOUTUBE.name().equals(rawSource)) {
      return YOUTUBE;
    }
    return null;
  }
}
