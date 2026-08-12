package top.asimov.pigeon.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DownloadProcessRegistryTest {

  @Test
  void preventsDuplicateRegistrationAndTerminatesClaimedProcess() throws IOException, InterruptedException {
    DownloadProcessRegistry registry = new DownloadProcessRegistry();
    Process first = new ProcessBuilder("sh", "-c", "sleep 30").start();
    Process duplicate = new ProcessBuilder("sh", "-c", "sleep 30").start();

    try {
      assertTrue(registry.register("episode", first));
      assertFalse(registry.register("episode", duplicate));
      assertTrue(registry.terminate("episode"));
      assertTrue(first.waitFor(2, TimeUnit.SECONDS));
    } finally {
      registry.terminateProcessTree(first);
      registry.terminateProcessTree(duplicate);
      registry.unregister("episode", first);
    }
  }
}
