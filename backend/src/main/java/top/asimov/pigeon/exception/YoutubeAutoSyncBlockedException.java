package top.asimov.pigeon.exception;

public class YoutubeAutoSyncBlockedException extends BusinessException {

  public YoutubeAutoSyncBlockedException(String message) {
    super(429, message);
  }
}
