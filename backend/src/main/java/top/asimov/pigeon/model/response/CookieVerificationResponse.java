package top.asimov.pigeon.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CookieVerificationResponse {

  private String platform;
  private boolean authenticated;
  private String sessionStatus;
  private String message;
}
