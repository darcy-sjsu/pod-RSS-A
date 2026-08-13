package top.asimov.pigeon.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("cookie_config")
public class CookieConfig {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String platform;
  private String cookiesContent;
  private Boolean enabled;
  private String sourceType;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  private String sessionStatus;
  private Boolean autoRefreshEnabled;
  private Integer rotateIntervalSeconds;
  private Integer rotateFailureCount;

  // Clearing these is meaningful: a fresh upload has no failure and no schedule yet. The default
  // update strategy skips null fields, which would leave a stale verdict behind forever.
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDateTime lastRotatedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDateTime nextRotateAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDateTime lastCheckedAt;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String lastFailureReason;
}
