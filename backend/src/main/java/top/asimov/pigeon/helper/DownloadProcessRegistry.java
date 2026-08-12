package top.asimov.pigeon.helper;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DownloadProcessRegistry {

  private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

  public boolean register(String episodeId, Process process) {
    if (episodeId == null || process == null) {
      return false;
    }
    return activeProcesses.putIfAbsent(episodeId, process) == null;
  }

  public void unregister(String episodeId, Process process) {
    if (episodeId == null || process == null) {
      return;
    }
    activeProcesses.remove(episodeId, process);
  }

  public boolean terminate(String episodeId) {
    Process process = activeProcesses.get(episodeId);
    if (process == null) {
      return false;
    }
    terminateProcessTree(process);
    return true;
  }

  public void terminateProcessTree(Process process) {
    if (process == null) {
      return;
    }

    ProcessHandle handle = process.toHandle();
    handle.descendants().forEach(ProcessHandle::destroy);
    handle.destroy();
    try {
      if (process.waitFor(10, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }

    handle.descendants()
        .filter(ProcessHandle::isAlive)
        .forEach(ProcessHandle::destroyForcibly);
    if (handle.isAlive()) {
      handle.destroyForcibly();
    }
  }

  @PreDestroy
  public void terminateAll() {
    if (activeProcesses.isEmpty()) {
      return;
    }
    log.info("[download] terminating active processes during shutdown: count={}",
        activeProcesses.size());
    activeProcesses.forEach((episodeId, process) -> {
      log.info("[download] terminating active process during shutdown: episodeId={}", episodeId);
      terminateProcessTree(process);
    });
    activeProcesses.clear();
  }
}
