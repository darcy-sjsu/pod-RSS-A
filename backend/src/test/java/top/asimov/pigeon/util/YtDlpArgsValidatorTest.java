package top.asimov.pigeon.util;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.asimov.pigeon.exception.BusinessException;

class YtDlpArgsValidatorTest {

  @Test
  void blocksArgumentsThatOverrideManagedDownloadSafety() {
    for (List<String> arguments : List.of(
        List.of("-f", "worst"),
        List.of("--format=worst"),
        List.of("--no-cookies"),
        List.of("--force-overwrites"),
        List.of("--exec-after-download", "command"),
        List.of("--download-archive", "archive.txt"))) {
      assertThrows(BusinessException.class, () -> YtDlpArgsValidator.validate(arguments));
    }
  }
}
