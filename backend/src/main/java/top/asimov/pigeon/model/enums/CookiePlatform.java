package top.asimov.pigeon.model.enums;

public enum CookiePlatform {

  YOUTUBE,
  RUMBLE;

  public static CookiePlatform fromFeedSource(String rawSource) {
    if (rawSource != null
        && FeedSource.YOUTUBE.name().equalsIgnoreCase(rawSource.trim())) {
      return YOUTUBE;
    }
    return null;
  }
}
