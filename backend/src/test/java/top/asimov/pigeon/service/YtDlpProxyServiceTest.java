package top.asimov.pigeon.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class YtDlpProxyServiceTest {

  @Test
  void redactsDownloadCredentialsAndTokens() {
    YtDlpProxyService service = new YtDlpProxyService(null);

    String redacted = service.redactCommand(List.of(
        "yt-dlp",
        "--cookies", "/tmp/private-cookies.txt",
        "--extractor-args=youtube:po_token=secret",
        "--add-header", "Authorization: Bearer secret"));

    assertEquals(
        "yt-dlp --cookies *** --extractor-args=*** --add-header ***",
        redacted);
  }
}
