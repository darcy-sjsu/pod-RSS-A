package top.asimov.pigeon.service.notification;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.asimov.pigeon.model.entity.NotificationConfig;
import top.asimov.pigeon.model.enums.CookiePlatform;
import top.asimov.pigeon.service.NotificationConfigService;
import top.asimov.pigeon.service.SystemConfigService;

/**
 * Tells the operator that a platform cookie session died and needs a fresh sign-in.
 *
 * <p>Sent once per transition into the invalid state, not on every failed refresh, so a flapping
 * network does not turn into a stream of alerts.
 */
@Slf4j
@Service
public class CookieSessionNotifyService {

  private final NotificationConfigService notificationConfigService;
  private final SystemConfigService systemConfigService;
  private final List<NotificationSender> notificationSenders;

  public CookieSessionNotifyService(NotificationConfigService notificationConfigService,
      SystemConfigService systemConfigService, List<NotificationSender> notificationSenders) {
    this.notificationConfigService = notificationConfigService;
    this.systemConfigService = systemConfigService;
    this.notificationSenders = notificationSenders;
  }

  public void notifySessionInvalidated(CookiePlatform platform, String reason) {
    NotificationConfig config = notificationConfigService.getCurrentConfig();
    List<NotificationSender> enabledSenders = notificationSenders.stream()
        .filter(sender -> sender.isEnabled(config))
        .toList();
    if (enabledSenders.isEmpty()) {
      return;
    }

    String baseUrl = systemConfigService.getCurrentConfig().getBaseUrl();
    NotificationMessage message = buildMessage(platform, reason, baseUrl);
    for (NotificationSender sender : enabledSenders) {
      try {
        sender.send(message, config);
      } catch (Exception e) {
        log.warn("[notification] cookie session alert delivery failed: channel={}",
            sender.channel(), e);
      }
    }
    log.info("[notification] cookie session alert sent: platform={} reason={}", platform, reason);
  }

  private NotificationMessage buildMessage(CookiePlatform platform, String reason, String baseUrl) {
    String timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .format(LocalDateTime.now().atOffset(ZoneOffset.UTC));
    String subject = "[PigeonPod] " + platform.name() + " cookies are no longer valid";

    StringBuilder text = new StringBuilder();
    text.append("PigeonPod can no longer refresh the ").append(platform.name())
        .append(" cookie session.\n\n");
    text.append("Downloads that need an account will keep failing until new cookies are uploaded.\n\n");
    text.append("Reason: ").append(reason).append('\n');
    text.append("Detected at: ").append(timestamp).append('\n');
    if (StringUtils.hasText(baseUrl)) {
      text.append("Settings: ").append(baseUrl).append("/user-setting").append('\n');
    }

    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("title", subject);
    variables.put("content", text.toString());
    variables.put("generatedAt", timestamp);
    variables.put("baseUrl", baseUrl == null ? "" : baseUrl);
    variables.put("total", "1");

    List<Map<String, Object>> payload = new ArrayList<>();
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("title", subject);
    item.put("platform", platform.name());
    item.put("reason", reason);
    item.put("detectedAt", timestamp);
    item.put("baseUrl", baseUrl);
    payload.add(item);

    return new NotificationMessage(subject, text.toString(), buildHtml(subject, platform, reason,
        timestamp, baseUrl), variables, payload);
  }

  private String buildHtml(String subject, CookiePlatform platform, String reason, String timestamp,
      String baseUrl) {
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f5f7fb;\">")
        .append("<div style=\"max-width:680px;margin:0 auto;padding:24px 16px;\">")
        .append("<div style=\"background:#ffffff;border:1px solid #e5e7eb;border-radius:16px;overflow:hidden;\">")
        .append("<div style=\"padding:28px 28px 20px;border-bottom:1px solid #e5e7eb;\">")
        .append("<div style=\"font:600 13px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;")
        .append("letter-spacing:.08em;text-transform:uppercase;color:#dc2626;\">PigeonPod</div>")
        .append("<div style=\"margin-top:10px;font:700 28px/1.2 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;")
        .append("color:#111827;\">").append(escapeHtml(subject)).append("</div>")
        .append("</div>")
        .append("<div style=\"padding:28px;\">")
        .append("<div style=\"font:400 18px/1.7 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#111827;\">")
        .append("PigeonPod can no longer refresh the ").append(escapeHtml(platform.name()))
        .append(" cookie session. Upload new cookies to restore account-only downloads.</div>")
        .append("<div style=\"margin-top:20px;padding:18px 20px;background:#f8fafc;border-radius:12px;\">")
        .append(metaRowHtml("Reason", reason))
        .append(metaRowHtml("Detected at", timestamp));
    if (StringUtils.hasText(baseUrl)) {
      html.append(metaRowHtml("Settings", baseUrl + "/user-setting"));
    }
    html.append("</div></div></div></div></body></html>");
    return html.toString();
  }

  private String metaRowHtml(String label, String value) {
    return "<div style=\"margin-top:8px;font:400 15px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
        + "color:#111827;\"><span style=\"font-weight:600;color:#4b5563;\">"
        + escapeHtml(label) + ":</span> " + escapeHtml(value) + "</div>";
  }

  private String escapeHtml(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
