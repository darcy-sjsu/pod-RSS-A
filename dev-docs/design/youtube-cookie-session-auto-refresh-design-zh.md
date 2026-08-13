# PigeonPod YouTube Cookie 会话自动续期设计方案

> **实施状态**：Phase 0、Phase 1、Phase 2 已全部落地（见第 16 节）。Phase 3（浏览器网页登录）仍为可选项，未实现。
> 落地过程中相对本文的偏差与新增约束记录在第 19 节。

## 1. Requirement Summary

- User request:
  - 为 yt-dlp 增加“跳转网页 → 用 Google 账号登录 youtube.com”的入口。
  - 核心目标是“按时间间隔自动获取 YouTube 网页里的 Cookies 并自动更新”，让 yt-dlp 始终使用不断更新的 YouTube Cookies。
  - 本文先输出实施方案，Phase 0/1/2 随后按本方案实现。
- Requirement type:
  - `enhancement`
- Assumptions:
  - 仍是单机、自托管、SQLite 部署模型，Cookie 仍是系统级（非按用户）配置。
  - 目标平台只有 `YOUTUBE`，沿用现有 `cookie_config` 多平台基础设施。
  - 用户可以接受“用一个小号登录”，并已知 yt-dlp 官方关于账号风险的警告。

## 2. Value Assessment

- User value:
  - 当前痛点是 Cookie 会在几小时到几天内失效，用户必须反复手动导出并上传 `cookies.txt`，失效期间自动下载会持续失败。
  - 在第 18 节假设成立的前提下，本方案把“一次登录”的有效期从数小时/数天延长到“直到账号主动登出或长期凭据过期”，把高频手工操作降为一次性操作。假设不成立时仍能拿到 Phase 0 的收益（见 16.1）。
  - 设置页能直接看到“会话是否有效、最后续期时间”，失效时主动通知，不再需要从下载失败日志里反推。
- Product/business value:
  - 把 Cookie 从“一次性上传的静态文本”升级为“有生命周期、可自愈、可观测的会话”，这是 YouTube 风控持续加强后的必要能力。
  - 复用已有 `cookie_config` 表、代理配置、通知配置和调度框架，不引入新的运行时依赖。
- Priority suggestion:
  - High

## 3. 当前基线（以代码为准）

1. Cookie 存储在 `cookie_config` 表，单行 `platform='YOUTUBE'`，明文 Netscape 文本：
   - `backend/src/main/java/top/asimov/pigeon/model/entity/CookieConfig.java`
   - `backend/src/main/resources/db/migration/V36__Add_cookie_config.sql`
2. 唯一的写入路径是用户手动上传，`sourceType` 固定 `UPLOAD`，校验只看 Netscape 头与 `youtube.com` 字样：
   - `backend/src/main/java/top/asimov/pigeon/service/CookieService.java`
   - `backend/src/main/java/top/asimov/pigeon/controller/CookieController.java`（`/api/cookies/**`）
   - `frontend/src/components/CookieConfigModal.jsx`
3. 下载时把库里的文本写成临时文件，通过 `--cookies` 传给 yt-dlp，`finally` 中删除临时文件：
   - `DownloadHandler.download()` 先解析 `FeedContext`，再按 `CookiePlatform.fromFeedSource()` 取平台并创建临时文件
   - `CookieService.createTempCookiesFile()` / `deleteTempCookiesFile()`
4. 自定义参数白名单屏蔽 `--cookies` / `--cookiefile` / `--no-cookies`，Cookie 必须由系统托管：
   - `backend/src/main/java/top/asimov/pigeon/util/YtDlpArgsValidator.java`
5. 已有可复用的基础设施：
   - 出站代理：`OutboundProxyHolder`（含 `toJavaNetProxy()`）与 `YtDlpProxyService`
   - 失败通知：`NotificationConfig` + SMTP / Webhook
   - 调度：`DownloadScheduler`（30 秒）、`ChannelSyncer`、`EpisodeCleaner` 等 `@Scheduled` 模式
   - PO Token：`bgutil-provider` sidecar 已在 `docker-compose.yml` 中
6. 容器内没有任何浏览器：运行镜像基于 `cgr.dev/chainguard/wolfi-base`，只装了 `ffmpeg`、`openjdk-17`、`python3`、`sqlite`、`deno`。

## 4. 已验证的外部技术事实

以下结论均由实测或直接阅读 yt-dlp `2026.07.04` 源码得到，不是来自二手文档。完整验证输出保存在本方案 PR 附带的 `cookie_refresh_technical_evidence.log` 中，按 `FACT 1` ~ `FACT 8` 分段。

### 4.1 `--cookies` 是双向的，当前实现把续期结果丢掉了

`--cookies FILE` 的官方语义是 “Netscape formatted file to read cookies from **and dump cookie jar in**”，`YoutubeDL.save_cookies()` 在运行结束时执行 `self.cookiejar.save()`。

也就是说：YouTube 在响应里下发的新 Cookie（例如 `SIDCC`、`__Secure-1PSIDCC`、`__Secure-3PSIDCC`）**已经被 yt-dlp 写回临时文件了**，但 PigeonPod 在 `finally` 中把这个文件删掉，从不合并回数据库。每次下载都在用同一份越来越旧的快照，这是一个确定存在的退化来源。

### 4.2 yt-dlp 判定“账号 Cookie 是否还有效”的确切规则

`extractor/youtube/_base.py`：

```python
@property
def _has_auth_cookies(self):
    yt_sapisid, yt_1psapisid, yt_3psapisid = self._get_sid_cookies()
    # YouTube doesn't appear to clear 3PSAPISID when rotating cookies (as of 2025-04-26)
    # But LOGIN_INFO is cleared and should exist if logged in
    has_login_info = 'LOGIN_INFO' in self._youtube_cookies
    return bool(has_login_info and (yt_sapisid or yt_1psapisid or yt_3psapisid))
```

并且每次请求后都会复检，失效时输出固定警告文本 `The provided YouTube account cookies are no longer valid.`。

两个可直接利用的结论：
- 必须保住的关键 Cookie 是 `LOGIN_INFO` 与 `SAPISID` / `__Secure-1PAPISID` / `__Secure-3PAPISID`。
- 那句警告是稳定的机器可读信号，可以从 yt-dlp 输出里解析出来，用于把会话立刻标记为失效。

### 4.3 Google OAuth 无法用于 yt-dlp，所以“跳转网页登录”不能做成 OAuth 回调

`_perform_login()` 对 `oauth` 用户名直接抛 `Login with OAuth is no longer supported`，对密码登录只输出警告。原因是 yt-dlp 走 InnerTube 私有 API，它只认会话 Cookie，而 OAuth / 设备码流程拿到的是 Data API v3 的 Bearer token，两套鉴权体系不通。

因此“用谷歌账号登录”只有一种可行形态：**在一个真实浏览器里完成 Google 登录，然后把会话 Cookie 搬到后端**。不存在“后端发起 OAuth 授权、回调换取 Cookie”的路径。

### 4.4 浏览器长期在线会持续轮换 Cookie，这会让导出的快照失效

yt-dlp Wiki 与大量 issue（#8227、#13014、#14872）的一致结论：YouTube 把“已打开的浏览器标签”当作安全信号，会频繁轮换账号 Cookie，并使之前导出的值失效。官方推荐姿势是隐身窗口登录 → 打开 `youtube.com/robots.txt` 阻断轮换脚本 → 导出 → 立刻关窗，且**该会话之后永不再在浏览器里使用**。

这一条直接否定了“保持一个浏览器登录态常驻 + 定时从网页里取 Cookie”的朴素实现：浏览器和 yt-dlp 会互相把对方手里的凭据变成过期值。

### 4.5 Google 存在可被服务端直接调用的轮换端点

浏览器侧的轮换最终落在 `POST /RotateCookies`。本环境实测（无 Cookie）：

```text
POST https://accounts.google.com/RotateCookies   -> http_status=403
POST https://accounts.youtube.com/RotateCookies  -> http_status=403
response_body=)]}'[["identity.hfcr",2147483647],["di",5]]
```

两个 host 都存在且响应体结构一致；`identity.hfcr` 是服务端声明的“下次轮换间隔”，未认证时为 `2147483647`（等价于“不要轮换”）。社区实测（Gemini-API、ytmusicapi 生态、notebooklm-py #345）表明：带上有效会话 Cookie 时该端点返回 `200`，下发新的 `__Secure-1PSIDTS` / `__Secure-3PSIDTS`，并把 `identity.hfcr` 置为 `600`（10 分钟），且当前不需要 DBSC 签名挑战；调用频率高于每分钟一次会触发限流。

这意味着**“定时自动更新 Cookie”可以完全由后端用一次普通 HTTPS 请求完成，不需要浏览器**。

### 4.6 自动化浏览器无法完成 Google 登录，真实浏览器 + CDP 可以

Playwright / Selenium / Puppeteer 启动的浏览器会被 Google 识别并拦在 “This browser or app may not be secure”，stealth 插件与各种 flag 组合普遍无效。可行做法是：用普通方式启动真实 Chrome/Chromium（独立 `--user-data-dir` + `--remote-debugging-port`），由人工完成登录，再通过 CDP attach 读取 Cookie。这决定了第 10 节浏览器方案的形态。

### 4.7 PO Token 在已登录时按账号绑定，现有 sidecar 已覆盖

`extractor/youtube/pot/utils.py` 中，GVS 场景下 `request.is_authenticated` 为真时用 `data_sync_id` 作为 content binding，否则用 `visitor_data`。yt-dlp 会把绑定值传给 provider，现有 `bgutil-provider` sidecar 无需改动。副作用是切换账号会使 PO Token 缓存失效，属正常行为。

### 4.8 DBSC 是中期风险

Chrome 146（Windows）与 150（macOS）已开始正式启用 Device Bound Session Credentials，把会话 Cookie 绑定到 TPM / Secure Enclave，导出的 Cookie 会很快失效。Chrome 文档明确说明：设备不支持安全硬件时优雅降级为标准行为。因此长期建议用 Firefox 导出，或在 Linux 容器里的浏览器中登录。

### 4.9 yt-dlp 的回写格式与读取格式不对称

`cookies.py` 中 `YoutubeDLCookieJar` 只在 `load()` 时识别 `#HttpOnly_` 前缀（读到就剥掉），`_really_save()` 写出的是 7 个裸字段，**不会还原该前缀**。此外它还会：

- 换成自己的文件头 `# This file is generated by yt-dlp.  Do not edit.`
- 把会话 Cookie 的 `expires` 从 `None` 改写为 `0`
- 把自己注入的 Cookie 一起导出（`_base.py` 中 `_set_cookie('.youtube.com', 'SOCS', 'CAI')` 与被改写的 `PREF`）

结论：**回写结果绝不能直接覆盖库里的内容**，否则会丢掉 httpOnly 标记、污染用户上传的原始文件，并把 yt-dlp 的内部状态混进主数据。必须按名字白名单做合并（见 9.5）。

### 4.10 实测：回写可以删除长期凭据

用一份手写的 Cookie 文件（含 `#HttpOnly_` 前缀的假 `LOGIN_INFO` 与 `SAPISID`）对真实视频跑一次 `yt-dlp --cookies <file> --simulate`，结果：

1. stderr 原样出现 4.2 中那句警告，证实这是可直接匹配的稳定信号。
2. 回写后的文件里 **`LOGIN_INFO` 与 `SAPISID` 整条消失了** —— YouTube 在响应里清除了它们，jar 里跟着删掉，`save()` 把这个删除持久化了。
3. `#HttpOnly_` 前缀与用户的文件头都没了，`PREF` 被 yt-dlp 改写成 `hl=en&tz=UTC`。

第 2 点是本方案最重要的安全约束来源：**如果直接用回写文件替换库里的内容，一次被风控的请求就会把长期凭据永久删掉**，即使凭据本身仍然可用（例如换个出口 IP 重试就能成功），会话也已经在库里被抹掉了。因此写回必须是“只允许更新新鲜度类 Cookie、永不接受删除”的白名单合并。

完整演示输出见验证日志的 `FACT 8` 段。

## 5. 关键结论：把“会话唯一所有者”作为设计核心

原始设想失败的根因不是“取 Cookie 的频率不够”，而是**同一个 Google 会话被两个主体同时持有**：浏览器按自己的节奏轮换，yt-dlp 拿着某个瞬间的快照，两边都会把对方的凭据变成过期值。提高抓取频率只会缩小竞态窗口，不会消除它。

因此本方案的核心决策是：

> **后端是这个 YouTube 会话的唯一所有者。**
> 浏览器只在“第一次拿到登录态”时出现一次，之后必须退出这个会话；此后由后端独占地维护会话新鲜度，并把最新凭据分发给 yt-dlp。

这个抽象一旦确立，用户的原始需求（定时自动更新 Cookie）就落在了第 4.5 节的服务端轮换端点上，反而**不再需要常驻浏览器**，实现成本和失败率都显著下降。

## 6. 设计目标与非目标

### 6.1 目标

1. 后端按服务端声明的间隔自动续期 YouTube 会话，并把结果写回 `cookie_config`。
2. 把 yt-dlp 每次运行后回写的 Cookie 合并回库，形成闭环，不再丢弃续期结果。
3. 从 yt-dlp 输出中识别“Cookie 已失效”，立刻反映到会话状态并通知用户。
4. 设置页可见会话状态、最后续期时间、下次续期时间，并支持手动立即续期与诊断。
5. 全部续期请求与 yt-dlp 使用同一出站代理，避免会话在多个出口 IP 上活动。
6. 可选提供“网页登录”入口，满足一次性获取登录态的体验诉求。

### 6.2 非目标

1. 本期不做 Cookie 加密存储，沿用现有明文决策。
2. 本期不做多账号 / 按 Feed 指定账号。
3. 不实现浏览器里那套混淆 JS 的完整轮换挑战流程，只走直接 POST 与 `RotateCookiesPage` 回退。
4. 不把浏览器打进主镜像，不默认暴露任何浏览器端口。
5. 不改变 `--cookies` 的注入方式与 `YtDlpArgsValidator` 白名单策略。

## 7. 推荐总体方案

分四层，按“零风险 → 有外部依赖”的顺序交付：

| 层 | 名称 | 作用 | 外部依赖 |
| --- | --- | --- | --- |
| L0 | Cookie 回写闭环 | yt-dlp 运行后把 jar 合并回库；解析失效警告 | 无 |
| L1 | 会话保鲜 | 定时 `POST /RotateCookies` 续期 `*PSIDTS` | Google 私有端点 |
| L2 | 状态与自愈 | 会话状态机、失效通知、手动续期与诊断 | 无 |
| L3 | 网页登录（可选） | 真实浏览器 sidecar + CDP 导入登录态 | 额外容器 |

核心决策：

1. 新增 `YoutubeCookieRotator` 负责单次轮换的 HTTP 细节，`CookieSessionService` 负责状态机与持久化，`CookieSessionScheduler` 负责到期扫描。
2. 会话状态与调度元数据直接加列到 `cookie_config`（与平台 1:1），不新建表。
3. 轮换只允许修改一组白名单 Cookie，永不允许端点删除或改写 `LOGIN_INFO`、`__Secure-1PSID` 这类长期凭据。
4. 下载链路与轮换链路用一把读写锁串起“取快照”和“写回合并”两个瞬间，不锁下载全程。
5. L3 默认关闭；登录导入完成后必须结束浏览器会话，以维持“唯一所有者”约束。

## 8. 数据模型设计

### 8.1 `cookie_config` 增列

```sql
-- V55__Add_cookie_session_refresh.sql
ALTER TABLE cookie_config ADD COLUMN session_status           TEXT    NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE cookie_config ADD COLUMN auto_refresh_enabled     INTEGER NOT NULL DEFAULT 1;
ALTER TABLE cookie_config ADD COLUMN rotate_interval_seconds  INTEGER NOT NULL DEFAULT 600;
ALTER TABLE cookie_config ADD COLUMN last_rotated_at          TIMESTAMP NULL;
ALTER TABLE cookie_config ADD COLUMN next_rotate_at           TIMESTAMP NULL;
ALTER TABLE cookie_config ADD COLUMN last_checked_at          TIMESTAMP NULL;
ALTER TABLE cookie_config ADD COLUMN rotate_failure_count     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE cookie_config ADD COLUMN last_failure_reason      TEXT    NULL;
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `session_status` | `UNKNOWN` / `ACTIVE` / `STALE` / `INVALID` |
| `auto_refresh_enabled` | 是否参与定时续期，便于用户临时关停 |
| `rotate_interval_seconds` | 服务端声明的间隔，夹紧在 `[60, 3600]` |
| `last_rotated_at` / `next_rotate_at` | 调度依据与前端展示 |
| `last_checked_at` | 最后一次状态判定时间（含 yt-dlp 侧判定） |
| `rotate_failure_count` | 连续失败次数，达到阈值转 `INVALID` 并通知 |
| `last_failure_reason` | 稳定英文短语，如 `HTTP_403`、`RATE_LIMITED`、`MISSING_AUTH_COOKIES` |

不新建表的理由：与 `platform` 天然 1:1，新建表会让所有读取路径多一次 join，且现在只有一个受管平台。若将来出现“多账号多档案”，再拆表更合适。

### 8.2 `source_type` 取值扩展

现有固定 `UPLOAD`，扩展为：

- `UPLOAD`：用户上传的 `cookies.txt`
- `ROTATED`：由 L1 续期后写回
- `YTDLP_WRITEBACK`：由 L0 合并 yt-dlp 回写结果
- `BROWSER_IMPORT`：由 L3 从浏览器导入

它只用于排障与前端展示“最近一次变更来源”，不参与任何选择逻辑。

## 9. 后端功能设计

### 9.1 模块划分

| 新增 | 职责 |
| --- | --- |
| `util/NetscapeCookieFile` | Netscape 文本 ↔ Cookie 列表的解析与序列化 |
| `service/cookie/YoutubeCookieRotator` | 单次轮换的 HTTP 调用、响应解析、Set-Cookie 提取 |
| `service/cookie/CookieSessionService` | 状态机、白名单合并、持久化、锁 |
| `scheduler/CookieSessionScheduler` | 到期扫描并触发续期 |
| `config/CookieRefreshProperties` | `@ConfigurationProperties` 配置 |
| `model/response/CookieSessionDiagnosticResponse` | 手动续期/诊断返回（只含名字与域，不含值） |

`CookieService` 的职责保持不变（上传校验、临时文件生命周期），新增能力放在 `CookieSessionService`，避免单个 service 继续膨胀。

### 9.2 Netscape 文本处理规则

格式为 7 个 Tab 分隔字段：`domain / includeSubdomains / path / secure / expiry / name / value`。需要处理的细节：

1. `#HttpOnly_` 前缀行是有效 Cookie，不能当注释丢弃；序列化时必须还原前缀。yt-dlp 自己不还原（见 4.9），我们必须还原，否则 `LOGIN_INFO`、`SID` 家族在多轮合并后会丢失 httpOnly 语义。
2. `expiry=0` 表示会话 Cookie，保留原值不要改写。
3. 保留原始行顺序与文件头（`# Netscape HTTP Cookie File`），减少 diff 噪声，也让上传校验继续通过。
4. 序列化必须使用 `\n` 与 Tab，不做对齐美化。
5. 解析失败的行原样保留并跳过，不因单行异常丢弃整份 Cookie。
6. Cookie 的唯一键是 `(domain, path, name)`，合并与查找都必须按三元组匹配，不能只按 `name`。

### 9.3 轮换算法

```text
rotate(platform, force):
  acquire writeLock(platform)
  config = load(platform)
  if !config.autoRefreshEnabled and !force -> skip(reason=DISABLED)
  if !force and now < config.nextRotateAt   -> skip(reason=NOT_DUE)

  jar = NetscapeCookieFile.parse(config.cookiesContent)
  if !jar.hasName("LOGIN_INFO") or !jar.hasAnyName(SAPISID_FAMILY):
      mark(INVALID, reason=MISSING_AUTH_COOKIES); return

  cookieHeader = jar.toCookieHeader(ROTATION_REQUEST_COOKIES)
  response = POST rotateUrl
      headers: Cookie, Content-Type=application/json,
               Origin=https://www.youtube.com, Referer=https://www.youtube.com/,
               User-Agent=<configured, 与 yt-dlp web client 对齐>
      body:    [0, "-0000000000000000000"]

  switch response.status:
    200:
      accepted = filterSetCookies(response, ROTATABLE_COOKIES)
      if accepted.isEmpty() -> mark(STALE, reason=NO_COOKIE_RETURNED); return
      jar.merge(accepted)                     // 含跨域镜像，见 9.4
      interval = clamp(parseHfcr(response.body), 60, 3600)
      persist(jar, sourceType=ROTATED, status=ACTIVE, failureCount=0,
              lastRotatedAt=now, nextRotateAt=now+interval)
    401, 403:
      failureCount++
      if failureCount >= maxConsecutiveFailures -> mark(INVALID) + notify
      else -> mark(STALE, nextRotateAt=now+backoff(failureCount))
    429:
      mark(STALE, reason=RATE_LIMITED, nextRotateAt=now+max(interval, 300))
    default / IOException:
      failureCount++; mark(STALE, nextRotateAt=now+backoff(failureCount))
  release writeLock
```

要点说明：

- **请求体**：首选社区验证过的哑值 `[0,"-0000000000000000000"]`。若真机验证发现被拒，回退为先 `GET https://accounts.youtube.com/RotateCookiesPage?origin=https://www.youtube.com&yt_pid=1`，从响应里用 `init\('(-?\d+)',` 提取初值再 POST。回退路径实现为可关闭的第二次尝试。
- **响应体解析**：Google 返回 `)]}'` 反 JSON 劫持前缀，必须先剥离该前缀再解析，再从 `[["identity.hfcr",600],...]` 中取间隔。解析失败时使用默认 600。
- **`ROTATION_REQUEST_COOKIES`**：只发送轮换所需的最小集合（`__Secure-1PSID`、`__Secure-3PSID`、`__Secure-1PSIDTS`、`__Secure-3PSIDTS`），不要把整份 jar 发给 `accounts.*`。
- **`ROTATABLE_COOKIES`**：只接受 `__Secure-1PSIDTS`、`__Secure-3PSIDTS`、`SIDCC`、`__Secure-1PSIDCC`、`__Secure-3PSIDCC`。任何针对其他名字的 Set-Cookie 一律忽略；任何空值或过期指令（`Max-Age<=0` / `Expires` 在过去）一律忽略。这条约束是安全阀：一次异常响应不能把长期凭据清空。

### 9.4 跨域镜像

yt-dlp 需要的是 `.youtube.com` 域下的 Cookie，而轮换端点可能只在 `.google.com` 下写回。处理规则：

1. 优先请求 `accounts.youtube.com`，其 Set-Cookie 更可能落在 `.youtube.com`。
2. 合并时对每个被接受的 Cookie，同时更新 jar 中 `.youtube.com` 与 `.google.com` 两个域下的同名条目（只更新已存在的条目，不新建域）。
3. 若两个域下都不存在该名字，则只按响应声明的域新增一条。

`*PSIDTS` 是同一个新鲜度令牌，跨域使用相同值是浏览器的既有行为，因此镜像是安全的。

### 9.5 与下载链路的并发协调

现状 `downloadTaskExecutor` 是 3 线程，加上调度线程，会有 4 个主体读写同一份 Cookie。设计如下：

1. 引入 `ReadWriteLock`（按 platform，单实例内存锁即可，SQLite 单机部署不需要分布式锁）。
2. `createTempCookiesFile()` 在读锁内完成“读库 + 写临时文件”，毫秒级。
3. 下载结束后在写锁内完成“读回临时文件 + 白名单合并 + 落库”。
4. 轮换在写锁内完成整个 `rotate()`。
5. **不在下载全程持锁**，否则 3 并发下载会被串行化。

写回合并的一致性规则：

- **合并，不覆盖**：以库里的 jar 为基准，只把 yt-dlp 回写文件中 `ROTATABLE_COOKIES` 白名单内的名字取出来更新到基准 jar 上。
- **永不接受删除**：回写文件里缺失的名字一律视为“本次没有更新”，绝不从库里删除。这是 4.10 实测出来的硬约束——直接覆盖会让一次被风控的请求永久抹掉 `LOGIN_INFO` / `SAPISID`。
- 一并规避 4.9 的格式副作用：不丢 httpOnly 前缀、不替换文件头、不把 yt-dlp 注入的 `SOCS` / `PREF` 写进主数据。
- 值未发生变化时不落库，避免每次下载都产生一次无意义的 `updated_at` 变更。
- 如果发现库里的 `updated_at` 已经晚于本次下载取快照的时刻（说明期间轮换器已经更新过），则**丢弃 yt-dlp 的回写**。轮换器是会话唯一所有者，陈旧值不得覆盖新鲜值。
- 合并后 `source_type` 记为 `YTDLP_WRITEBACK`，`session_status` 不因写回而改变。
- 临时文件在合并之后仍要删除，删除逻辑保持在原有 `finally` 中。

已接受的权衡：轮换可能发生在某次下载的元数据提取阶段，导致该次下载因凭据切换而失败。影响面有限（媒体流 URL 自带签名，传输阶段不依赖 Cookie），且失败会走现有指数退避重试。相比“下载期间禁止轮换”（长下载会把续期饿死 60 分钟）更划算。

### 9.6 失效检测

三个来源，成本从低到高：

1. **轮换结果**：`200` → `ACTIVE`，连续 `401/403` 达阈值 → `INVALID`。这是最便宜的探针，不需要额外请求。
2. **yt-dlp 输出解析**：下载或诊断输出里出现 `The provided YouTube account cookies are no longer valid` 时，立刻置 `INVALID` 并通知。这是权威信号，且零额外成本。注意匹配位置：这句警告出现在元数据提取阶段（下载最开头），而现有 `ProcessExecutionResult.outputTail()` 只保留输出尾部，长下载会把它挤掉。因此匹配必须在逐行读取流的时候做，用一个布尔标记带出来，不能只扫 `outputTail()`。
3. **手动深度校验**：设置页触发一次 `yt-dlp --simulate --skip-download --cookies <temp>`，沿用 `AccountService.testYtDlpProxy()` 的进程执行模式，返回是否命中上述警告。

`STALE` 表示“续期暂时失败但凭据仍可能可用”，不阻断下载；`INVALID` 表示“确认失效”，仍不阻断下载（避免误判导致完全不可用），但前端高亮提示并发通知。

### 9.7 出站一致性

所有轮换与校验请求必须复用 `OutboundProxyHolder`：

- 用 `RestClient` + `SimpleClientHttpRequestFactory.setProxy(settings.toJavaNetProxy())`。选它而不是 JDK `HttpClient`，是因为 JDK `HttpClient` 不支持 SOCKS5，而项目代理配置允许 `SOCKS5`，`SimpleClientHttpRequestFactory` 底层的 `HttpURLConnection` 支持 `Proxy.Type.SOCKS`。`RestClient` 已由 `spring-boot-starter-web` 提供，`WebhookNotificationSender` 已在用，不需要新依赖。
- 状态码必须自己接管：`RestClient` 默认对 4xx/5xx 抛异常，而 `403` / `429` 在本流程里是需要区分处理的正常分支，因此要用 `exchange()` 或注册 `onStatus` 放行，并从 `ClientHttpResponse.getHeaders()` 读取多值 `Set-Cookie`。
- 代理配置变更时重建 request factory（可在 `OutboundProxyHolder.apply()` 之后刷新，或每次按当前设置构造，频率极低）。
- User-Agent 由配置项统一提供，与 yt-dlp web client 对齐；不要让轮换请求带 Java 默认 UA。

### 9.8 API 设计

在现有 `/api/cookies/**`（`@SaCheckRole("admin")`）下扩展：

1. `GET /api/cookies` — 返回体增加会话字段：

```json
[
  {
    "platform": "YOUTUBE",
    "updatedAt": "2026-08-12T10:00:00",
    "sourceType": "ROTATED",
    "sessionStatus": "ACTIVE",
    "autoRefreshEnabled": true,
    "lastRotatedAt": "2026-08-12T10:00:00",
    "nextRotateAt": "2026-08-12T10:10:00",
    "rotateFailureCount": 0,
    "lastFailureReason": null
  }
]
```

2. `POST /api/cookies/{platform}/refresh` — 立即续期，返回诊断摘要：

```json
{
  "statusCode": 200,
  "sessionStatus": "ACTIVE",
  "rotatedCookieNames": ["__Secure-1PSIDTS", "__Secure-3PSIDTS"],
  "rotatedCookieDomains": [".youtube.com", ".google.com"],
  "nextIntervalSeconds": 600
}
```

3. `POST /api/cookies/{platform}/auto-refresh` — body `{"enabled": true}`。
4. `POST /api/cookies/{platform}/verify` — 深度校验（9.6 第 3 项）。
5. `POST /api/cookies/{platform}/import-from-browser` — L3 专用，默认因未配置浏览器地址而返回明确错误。

绝不返回任何 Cookie 值，只返回名字、域与状态。

### 9.9 配置项

```yaml
pigeon:
  cookie:
    refresh:
      enabled: true
      rotate-url: https://accounts.youtube.com/RotateCookies
      fallback-rotate-page-url: https://accounts.youtube.com/RotateCookiesPage
      min-interval-seconds: 60
      default-interval-seconds: 600
      max-interval-seconds: 3600
      max-consecutive-failures: 3
      request-timeout-seconds: 15
      user-agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    browser:
      cdp-base-url: ${PIGEON_COOKIE_BROWSER_CDP_URL:}
      login-url: ${PIGEON_COOKIE_BROWSER_LOGIN_URL:}
```

端点 URL 与请求体做成配置项，是因为它们是私有接口；一旦 Google 变更，用户可以先改配置自救，不必等新版本。

### 9.10 调度与日志

调度：`CookieSessionScheduler`，`@Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)`，扫描 `auto_refresh_enabled = 1 AND next_rotate_at <= now` 的行。1 分钟粒度足以支撑 10 分钟的续期节奏，也天然满足最小节流。执行放在现有 `channelSyncTaskExecutor` 或直接同步执行（单次请求，耗时可忽略）。

日志：新增 scope `[cookie-session]`，遵循 `dev-docs/standard/backend-logging-standards-zh.md`：

```java
log.info("[cookie-session] rotate completed: platform={} statusCode={} rotatedCount={} nextRotateAt={} elapsedMs={}",
    platform, statusCode, rotatedCount, nextRotateAt, elapsedMs);
log.warn("[cookie-session] rotate failed: platform={} statusCode={} reason={} failureCount={}",
    platform, statusCode, reason, failureCount);
log.warn("[cookie-session] session invalidated: platform={} reason=ytDlpWarning episodeId={}",
    platform, episodeId);
```

禁止输出 Cookie 值，只允许输出名字、域、状态与计数。

### 9.11 通知

复用 `NotificationConfig`：会话从非 `INVALID` 转为 `INVALID` 时发一次通知，包含平台、失败原因、最后成功续期时间与“请重新导出 Cookie”的指引。状态恢复为 `ACTIVE` 时不发通知，避免噪声；同一次失效只通知一次，直到状态恢复才重置。

## 10. 网页登录（L3，可选层）

### 10.1 三个备选对比

| 方案 | 用户体验 | Google 风控 | 服务端成本 | 结论 |
| --- | --- | --- | --- | --- |
| B1 真实浏览器 sidecar + Web 远程桌面 + CDP 导入 | 最接近“点一下跳转网页登录” | 真实浏览器，人工登录，不触发自动化拦截 | 额外容器约 1.5 GB、额外端口、需设访问密码 | **推荐作为可选增强** |
| B2 浏览器扩展，从用户本机推送 Cookie | 需要安装扩展 | 最好（就是用户自己的浏览器） | 服务端几乎为零，但要维护并分发扩展 | Later |
| B3 主机侧一次性导出脚本（Firefox profile + `--cookies-from-browser`） | 仍是手工，但比“找扩展导出”可靠 | 无 | 零 | **MVP 期的官方推荐姿势** |

### 10.2 B1 实现要点

1. `docker-compose.yml` 增加 profile 化的 service（例如 `--profile browser-login`），默认不启动。镜像选择带 Web 访问的桌面 Chromium（如 `linuxserver/chromium`，自带 KasmVNC Web 界面），必须设置访问密码，且默认只绑定到内网。
2. 启动参数追加 `--remote-debugging-port=9222`。CDP 默认只监听 localhost 且校验 `Host` 头，跨容器访问需要在 sidecar 内做端口转发，或请求时显式把 `Host` 设为 `localhost`。
3. 设置页“使用 Google 账号登录 YouTube”按钮跳转到 sidecar 的 Web 地址（`pigeon.cookie.browser.login-url`），用户在真实浏览器里完成登录。
4. 用户回到设置页点“导入登录态”，后端通过 CDP `Storage.getCookies` 取 `.youtube.com` / `.google.com` 域下的 Cookie，转成 Netscape 文本，走与上传相同的校验后落库，`source_type=BROWSER_IMPORT`。
5. **导入成功后必须结束浏览器会话**：关闭标签并清理 profile，或直接停掉 sidecar。这是第 5 节“唯一所有者”约束的强制落地点，UI 上要明确提示，不能留给用户自觉。
6. 风险提示：sidecar 暴露的是一个可交互浏览器，等于在内网开了一个可访问任意站点的入口；文档必须要求设密码、不要映射到公网、用完即停。

### 10.3 B3 作为 MVP 期推荐姿势

在 `scripts/` 下提供一个一次性导出脚本，让用户在自己的机器上执行，避免依赖来源不明的浏览器扩展：

```bash
# 1) 用 Firefox 的隐私窗口登录 YouTube，打开 https://www.youtube.com/robots.txt，然后关闭窗口
# 2) 让 yt-dlp 直接读 Firefox 的 cookie 库并导出成 Netscape 文件
yt-dlp --cookies-from-browser firefox --cookies youtube-cookies.txt \
       --simulate --skip-download "https://www.youtube.com/watch?v=<any-video-id>"
# 3) 把 youtube-cookies.txt 上传到 PigeonPod 设置页
```

利用的正是 4.1 的双向语义：`--cookies` 会把从浏览器读出的 jar 落成文件。选 Firefox 是因为它的 cookie 库未加密且不受 DBSC 影响（见 4.8）。

## 11. 前端交互设计

`CookieConfigModal` 从“上传/清除”升级为“会话管理”：

1. 状态区：状态 Badge（`ACTIVE` 绿 / `STALE` 黄 / `INVALID` 红 / `UNKNOWN` 灰）、最后续期时间、下次续期时间、最近变更来源。
2. 开关：`自动续期`（`Switch`，对应 `auto-refresh` 接口）。
3. 按钮：`立即续期`（展示返回的诊断摘要：状态码、被更新的 Cookie 名与域、下次间隔）、`校验登录态`、`清除 Cookie`。
4. 上传区保留，但把说明文案改成 yt-dlp 官方姿势：隐身窗口登录 → 打开 `youtube.com/robots.txt` → 导出 → 立即关窗且不再使用该会话；并补充“Chrome 已启用 DBSC，建议用 Firefox 导出”。
5. L3 启用时（后端返回 `browserLoginAvailable=true`）额外显示 `用 Google 账号登录` 与 `从浏览器导入登录态` 两个按钮，未启用时不渲染。
6. 轮询：弹窗打开期间每 10 秒刷新一次状态即可，不需要 3 秒级轮询。

i18n 沿用扁平 key，新增：`cookie_session_status_active` / `_stale` / `_invalid` / `_unknown`、`cookie_session_auto_refresh`、`cookie_session_refresh_now`、`cookie_session_verify`、`cookie_session_last_rotated_at`、`cookie_session_next_rotate_at`、`cookie_session_export_guide`、`cookie_browser_login`、`cookie_browser_import`。

## 12. 数据迁移与兼容

1. `V55__Add_cookie_session_refresh.sql` 只做加列，全部带默认值，老数据自动获得 `session_status='UNKNOWN'`、`auto_refresh_enabled=1`、`rotate_interval_seconds=600`。
2. `next_rotate_at` 留 `NULL`，调度器把 `NULL` 视为“立即到期”，升级后第一轮扫描即尝试续期，随后进入正常节奏。
3. 现有上传接口语义不变，上传成功后重置 `session_status='UNKNOWN'`、`rotate_failure_count=0`、`next_rotate_at=NULL`，让新 Cookie 立刻参与续期。
4. `system_config.cookies_content` 仍是死列，本期不动，与既有方案的处置结论保持一致。

## 13. 安全影响

1. Cookie 仍明文存储（既有决策）。本方案让明文内容被更频繁地读写，因此临时文件权限收紧逻辑要保留，并把写回文件也纳入同样处理。
2. 诊断与状态接口只返回名字、域和状态，永不返回值；日志同样只打名字。
3. 轮换请求只发送最小 Cookie 子集给 `accounts.*`，减少凭据暴露面。
4. 白名单合并机制保证外部响应无法删除或篡改长期凭据。
5. L3 的浏览器 sidecar 是本方案里唯一实质性提高攻击面的部分，因此默认关闭、必须设密码、用完即停。
6. 继续在 UI 强调使用小号：yt-dlp 官方明确提示账号可能被限制或封禁。

## 14. 架构适配分析

- Value Alignment：直接解决“Cookie 频繁失效导致自动下载中断”，与项目“自动化订阅下载”的核心价值一致。
- Feasibility：L0/L2 完全在现有代码内，零外部依赖；L1 依赖一个已验证存在的私有端点；L3 是可选项。
- Architecture Fit：Good。落在既有 `controller/service/scheduler/mapper` 分层与 `cookie_config` 表上，无新中间件、无新依赖（`RestClient` 来自 `spring-boot-starter-web`）。
- Data and Migration Impact：低。仅加列，无数据重算。
- API and Contract Impact：低。`GET /api/cookies` 增字段（向后兼容），新增 4 个管理接口。
- Security and Compliance：中。见第 13 节。
- Performance and Cost：极低。每 10 分钟一次 HTTPS 请求，调度器每分钟一次小表查询。
- Testability and Operability：好。Netscape 解析、白名单合并、间隔解析、状态机都是纯函数或可 stub 的 HTTP 交互。

## 15. 测试方案

### 15.1 后端单元测试

1. `NetscapeCookieFile`：往返一致性、`#HttpOnly_` 前缀保留、`expiry=0` 会话 Cookie、非法行原样保留。
2. 白名单合并：
   - 只接受 `ROTATABLE_COOKIES`
   - 拒绝删除 `LOGIN_INFO` / `__Secure-1PSID`
   - 拒绝空值与过期指令
   - 跨域镜像只更新已存在的域
3. 响应解析：剥离 `)]}'` 前缀后取 `identity.hfcr`；解析失败回落默认值；间隔夹紧到 `[60, 3600]`。
4. 状态机：`200 → ACTIVE`、连续 `403` 达阈值 → `INVALID` 且只通知一次、`429 → STALE` 且退避不少于 300 秒、缺少鉴权 Cookie → 直接 `INVALID`。
5. 写回一致性（直接对应 4.10 的实测现象）：
   - 输入一份缺少 `#HttpOnly_` 前缀、带 yt-dlp 文件头、且额外含 `SOCS` / `PREF` 的回写文件，断言合并后基准 jar 的 httpOnly 前缀不丢失、文件头不变、`SOCS` / `PREF` 未进入库
   - 输入一份**缺少 `LOGIN_INFO` 与 `SAPISID`** 的回写文件，断言库里这两条依然存在
   - 库里 `updated_at` 在下载期间被更新时丢弃 yt-dlp 回写
   - 值未变化时不产生落库操作
6. yt-dlp 警告解析：命中官方警告文本即置 `INVALID`。

### 15.2 后端集成测试

1. 用本地 stub server 模拟 `200 / 403 / 429 / 5xx`，断言落库内容与状态字段。
2. Flyway 迁移测试，参考现有 `RemoveBilibiliSupportMigrationTest` 的写法，断言加列与默认值。
3. `GET /api/cookies` 不泄漏 `cookiesContent`。
4. 并发测试：多线程同时执行“取快照 + 写回”与“轮换”，断言最终内容不丢 Cookie、不产生重复条目。

### 15.3 真机验证（必须由持有真实账号的人执行）

Phase 1 只提供手动 `refresh` 与诊断返回，用于验证第 18 节的假设，验证通过后再开定时任务。

## 16. 交付计划

Phase 0 / 1 / 2 已实现，Phase 3 与 Later 仍未开始。

### 16.1 Phase 0（零风险，先做）— 已实现

1. `NetscapeCookieFile` 工具与单测。
2. yt-dlp 回写合并闭环（L0）与读写锁。
3. yt-dlp 失效警告解析 + `session_status` 落库（L2 的一部分）。
4. `V55` 迁移与 `GET /api/cookies` 字段扩展。
5. 前端状态展示。

Phase 0 不依赖任何未验证的外部行为：它消除的是“每次下载都用一份越来越旧的快照”这个确定存在的退化来源，并让会话状态第一次变得可观测。即使后续 L1 被证明不可行，这部分收益依然成立。

### 16.2 Phase 1（验证私有端点）— 已实现

1. `YoutubeCookieRotator` + `POST /api/cookies/{platform}/refresh` 手动触发与诊断返回。
2. 代理复用与 UA 对齐。
3. 用真实账号验证第 18 节假设。

### 16.3 Phase 2（自动化）— 已实现

1. `CookieSessionScheduler` 定时续期、退避、限流。
2. 失效通知。
3. 自动续期开关与深度校验接口。
4. 上传引导文案改为官方姿势 + DBSC 提示。

### 16.4 Phase 3（可选，网页登录）— 未实现

1. profile 化浏览器 sidecar。
2. CDP 导入接口与“导入后结束会话”强约束。
3. 文档与安全提示。

### 16.5 Later

1. Cookie 加密存储。
2. 多账号 / 按 Feed 指定账号。
3. 浏览器扩展推送（B2）。

### 16.6 Estimated Complexity

- Phase 0：`M`
- Phase 1 + 2：`M`
- Phase 3：`L`

## 17. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `RotateCookies` 是私有端点，Google 可能变更或加签名挑战 | 自动续期失效 | 端点与请求体做成配置项；失败仅降级为 `STALE` + 通知，不影响现有手动上传能力；保留 `RotateCookiesPage` 回退路径 |
| DBSC 推广后从 Chrome 导出的 Cookie 迅速失效 | 一次性登录频率上升 | 引导用 Firefox 导出或在 Linux 容器浏览器登录；文案明确说明 |
| 账号被 YouTube 限制或封禁 | 用户账号损失 | UI 与文档强调使用小号；不默认开启任何登录能力 |
| 会话在多个出口 IP 上活动触发风控 | Cookie 更快失效 | 强制所有续期请求复用 `OutboundProxyHolder` |
| 轮换与并发下载竞态 | 偶发下载失败 | 读写锁 + 白名单合并 + 以轮换器为权威；失败走既有指数退避 |
| 异常响应清空长期凭据 | 会话直接报废 | 白名单只允许更新新鲜度类 Cookie，拒绝删除指令 |
| 浏览器 sidecar 暴露内网交互式浏览器 | 安全面扩大 | 默认关闭、profile 化、必须设密码、用完即停 |
| 多用户模式下 Cookie 是系统级共享 | 所有用户共用同一 YouTube 身份 | 沿用现状语义，在设置页文案中说明；多账号能力放 Later |

## 18. 需要真机验证的假设

Phase 1 的诊断接口就是为验证这些假设而存在的：

1. 带有效会话 Cookie 时 `POST https://accounts.youtube.com/RotateCookies` 返回 `200`，且 `identity.hfcr` 为 600 量级。
2. 响应 Set-Cookie 覆盖的域是 `.youtube.com` 还是仅 `.google.com`（决定跨域镜像是否必需）。
3. 哑请求体 `[0,"-0000000000000000000"]` 是否被接受；若被拒，是否必须走 `RotateCookiesPage` 提取初值。
4. 轮换后 `LOGIN_INFO` 是否仍在，yt-dlp 是否仍判定为已登录（这是成败的判定标准）。
5. 是否需要同步维护 `SIDCC` 家族才能长期稳定。
6. 实际限流阈值与 `429` 行为。
7. 只由后端持有会话（浏览器已关闭）时，连续续期能维持多长时间的有效期。

若假设 1 或 4 不成立，则 L1 不可行，方案退化为“Phase 0 的回写闭环 + 状态可观测 + 手动更新引导”，用户价值仍然为正，只是无法做到完全免手工。

## 19. 落地记录：与本方案的偏差和新增约束

Phase 0 / 1 / 2 实现过程中发现了几个方案里没预见到的点，记录在这里，避免后续维护者重新踩一遍。

### 19.1 回写必须"合并而不覆盖"的强度比预想更高

方案第 4.10 节已经预判到这一点，实测进一步确认：yt-dlp 的回写文件在真实流量下会**整条丢掉** YouTube 清除掉的 `LOGIN_INFO` 与 `SAPISID`。因此合并规则里"缺失的名字一律视为没更新、绝不删除"是硬性约束，不是防御性冗余。这条规则由 `NetscapeCookieFileTest` 与 `CookieServiceTest` 各自覆盖。

### 19.2 真实端点在凭据无效时返回 401，不是 403

设计时只探测到未携带 Cookie 的 403。带上无效凭据实测返回 `401`。两者都按"拒绝"处理并计入连续失败次数，`429` 与网络失败则不计入，只做退避。

### 19.3 MyBatis-Plus 默认不写回 null 字段

`session_status` 之外的会话字段需要能被清空（重新上传后应清掉失败原因和排期）。MyBatis-Plus 默认的更新策略会跳过 null 字段，导致清空操作静默失效。`lastRotatedAt`、`nextRotateAt`、`lastCheckedAt`、`lastFailureReason` 因此标注了 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`，并由一个反射断言守住。

### 19.4 `HttpURLConnection` 会静默丢弃 `Origin` 头

`Origin` 属于 JDK 的受限请求头，`SimpleClientHttpRequestFactory` 底层的 `HttpURLConnection` 会直接丢掉它。因此续期请求默认改用 JDK `HttpClient`（`JdkClientHttpRequestFactory`），只有配置了 SOCKS5 代理时才退回旧客户端——代价是那种情况下 `Origin` 仍然发不出去。这个取舍写在 `YoutubeCookieRotator.buildRestClient()` 的注释里。

### 19.5 失效警告要扫全量输出，不能只看 tail

`ProcessExecutionResult.outputTail()` 只保留输出尾部，而这句警告出现在元数据提取阶段，一次大文件下载足以把它挤出缓冲区。检测改为扫描完整的进程输出日志文件。

### 19.6 尚未实现的方案条目

- `RotateCookiesPage` 提取初值的回退路径没有实现。当前只走直接 POST，端点与请求体是配置项，真机验证发现哑请求体被拒时再补。
- Phase 3 的浏览器 sidecar 登录未实现。
- 第 18 节的 7 条假设仍需持有真实账号的人验证：成功路径目前是对照真实响应结构的本地 stub 验证的，失败路径与失效检测则已在真实流量上验证过。

## 20. Decision

- Recommendation:
  - Proceed with constraints
- Reasoning:
  - 用户的目标（让 yt-dlp 始终用上不断更新的 Cookie）是正确且高价值的，但原始实现设想（常驻浏览器 + 定时抓取网页 Cookie）与 YouTube 的轮换机制直接冲突，会陷入两个主体互相失效的循环。
  - 正确的抽象是“后端作为会话唯一所有者”：浏览器只负责一次性登录，续期由后端用一次普通 HTTPS 请求完成。这样既满足了“定时自动更新”的核心诉求，又省掉了容器内浏览器这个最大的成本项。
  - 建议按 Phase 0 → 1 → 2 推进：先落地零风险的回写闭环与可观测性，再用手动诊断验证私有端点，最后才开定时自动化。
  - “跳转网页登录”作为 Phase 3 的可选增强交付，默认关闭；MVP 阶段用主机侧一次性导出脚本承接“第一次怎么拿到登录态”。
