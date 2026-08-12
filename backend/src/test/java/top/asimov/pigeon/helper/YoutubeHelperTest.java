package top.asimov.pigeon.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import top.asimov.pigeon.config.ProxyExecutionScope;

@ExtendWith(MockitoExtension.class)
class YoutubeHelperTest {

  @Mock
  private MessageSource messageSource;
  @Mock
  private YoutubeApiExecutor youtubeApiExecutor;
  @Mock
  private YoutubeServiceFactory youtubeServiceFactory;
  @Mock
  private ProxyExecutionScope proxyExecutionScope;

  private YoutubeHelper youtubeHelper;

  @BeforeEach
  void setUp() {
    youtubeHelper = new YoutubeHelper(
        messageSource, youtubeApiExecutor, youtubeServiceFactory, proxyExecutionScope);
  }

  @Test
  void extractsExactHandlesWithoutQueryOrPathSuffixes() {
    assertEquals("LofiGirl", youtubeHelper.getHandleFromUrl("@LofiGirl"));
    assertEquals("LofiGirl",
        youtubeHelper.getHandleFromUrl("https://www.youtube.com/@LofiGirl?si=tracking"));
    assertEquals("LegacyName",
        youtubeHelper.getHandleFromUrl("https://www.youtube.com/c/LegacyName/videos"));
    assertNull(youtubeHelper.getHandleFromUrl("https://www.youtube.com/user/LegacyUser"));
  }

  @Test
  void extractsLegacyUsernames() {
    assertEquals("LegacyUser",
        youtubeHelper.getUsernameFromUrl("https://www.youtube.com/user/LegacyUser/videos"));
  }
}
