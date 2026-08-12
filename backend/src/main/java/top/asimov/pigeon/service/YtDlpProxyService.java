package top.asimov.pigeon.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.asimov.pigeon.config.OutboundProxyHolder;
import top.asimov.pigeon.model.entity.SystemConfig;

@Service
public class YtDlpProxyService {

  private static final Set<String> SENSITIVE_VALUE_FLAGS = Set.of(
      "--cookies", "--cookiefile", "--add-header", "--extractor-args");

  private final OutboundProxyHolder proxyHolder;

  public YtDlpProxyService(OutboundProxyHolder proxyHolder) {
    this.proxyHolder = proxyHolder;
  }

  public void appendCurrentProxyArgs(List<String> command) {
    appendProxyArgs(command, proxyHolder.current());
  }

  public void appendProxyArgs(List<String> command, SystemConfig config) {
    appendProxyArgs(command, proxyHolder.from(config));
  }

  public void appendProxyArgs(List<String> command, OutboundProxyHolder.OutboundProxySettings settings) {
    if (command == null || settings == null || !settings.enabled()) {
      return;
    }
    String proxyUrl = settings.toYtDlpProxyUrl();
    if (!StringUtils.hasText(proxyUrl)) {
      return;
    }
    command.add("--proxy");
    command.add(proxyUrl);
  }

  public String redactCommand(List<String> command) {
    if (command == null || command.isEmpty()) {
      return "";
    }
    List<String> redacted = new ArrayList<>(command.size());
    for (int i = 0; i < command.size(); i++) {
      String token = command.get(i);
      if ("--proxy".equals(token)) {
        redacted.add(token);
        if (i + 1 < command.size()) {
          redacted.add(maskProxyToken(command.get(i + 1)));
          i++;
        }
        continue;
      }
      if (token != null && token.startsWith("--proxy=")) {
        redacted.add("--proxy=" + maskProxyToken(token.substring("--proxy=".length())));
        continue;
      }
      if (SENSITIVE_VALUE_FLAGS.contains(token)) {
        redacted.add(token);
        if (i + 1 < command.size()) {
          redacted.add("***");
          i++;
        }
        continue;
      }
      String sensitivePrefix = sensitivePrefix(token);
      if (sensitivePrefix != null) {
        redacted.add(sensitivePrefix + "***");
        continue;
      }
      redacted.add(token);
    }
    return String.join(" ", redacted);
  }

  private String sensitivePrefix(String token) {
    if (token == null) {
      return null;
    }
    for (String flag : SENSITIVE_VALUE_FLAGS) {
      String prefix = flag + "=";
      if (token.startsWith(prefix)) {
        return prefix;
      }
    }
    return null;
  }

  private String maskProxyToken(String token) {
    if (!StringUtils.hasText(token)) {
      return token;
    }
    int schemeIndex = token.indexOf("://");
    int atIndex = token.lastIndexOf('@');
    if (schemeIndex < 0 || atIndex < 0 || atIndex <= schemeIndex + 3) {
      return token;
    }
    return token.substring(0, schemeIndex + 3) + "***:***" + token.substring(atIndex);
  }
}
