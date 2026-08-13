package top.asimov.pigeon.model.response;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Diagnostic outcome of one refresh attempt.
 *
 * <p>Deliberately carries cookie names and domains but never cookie values, so it is safe to show
 * in the settings page and to log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CookieRotationResponse {

  private String platform;
  private String outcome;
  private String reason;
  private Integer statusCode;
  private String sessionStatus;
  private List<String> rotatedCookieNames;
  private List<String> rotatedCookieDomains;
  private Integer nextIntervalSeconds;
  private LocalDateTime nextRotateAt;
}
