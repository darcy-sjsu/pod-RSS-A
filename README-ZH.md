<div align="center">
  <img src=".github/docs-assets/logo-with-brand.png" alt="pigeonpod" width="260" />
  <h2>随时随地收听 YouTube。</h2>
</div>

<div align="center">

[English](README.md)
</div>

> [!NOTE]
> 本简体中文 README 只作为轻量项目入口。
> 更详细的设计与架构文档见仓库中的 `dev-docs/`。

## PigeonPod 是什么

PigeonPod 是一个面向技术用户的自托管项目，可以把 YouTube 频道和 YouTube 播放列表转换成可订阅的播客 RSS，并按你的规则自动同步、下载和管理节目。

它更适合这类用户：

- 愿意自己部署和维护服务
- 希望把 YouTube 内容接入播客客户端
- 需要对自动下载、过滤、保留策略、存储方式有控制权

## 核心能力

- **🎯 智能订阅与预览**：几秒内订阅 YouTube 的频道与播放列表。
- **🎞 单个 YouTube 视频订阅**：将单个 YouTube 视频转换为自动生成的播放列表订阅。
- **📻 安全的 RSS 播客订阅**：为任何播客客户端生成受保护的标准 RSS。
- **🎦 灵活的音视频输出**：按需下载音频或视频，并控制质量与格式。
- **🤖 自动同步与历史补齐**：持续更新订阅内容，并按需补齐历史节目。
- **👥 多用户及权限控制**：支持多用户账号管理与角色权限控制，仅限管理员修改系统配置与订阅内容。
- **🍪 自定义 Cookie 支持**：使用 YouTube Cookies，更稳定访问受限内容。
- **🌍 代理支持的网络访问**：让 YouTube API 与 yt-dlp 支持自定义代理。
- **🔗 单集节目一键分享**：通过公开页面分享单集，无需登录即可直接播放。
- **📦 快速批量下载**：高效搜索、勾选并排队历史节目。
- **📊 下载面板与批量操作**：跟踪任务状态，并批量重试、取消或删除。
- **🔔 下载失败摘要与通知**：当自动重试耗尽后，通过 Email 或 Webhook 接收失败任务摘要。
- **🔍 按订阅过滤与保留策略**：用关键词、时长和集数限制控制同步范围。
- **⏱ 更智能的新节目下载**：延迟自动下载，提升新视频处理效果。
- **🎛 可定制订阅与内置播放器**：自定义标题和封面，并在网页中直接播放节目。
- **🧩 节目管理与控制**：下载、重试、取消和删除节目时同步清理文件。
- **🔓 受信环境自动登录**：在受信访问控制后方部署时可跳过手动登录。
- **🔒 内置 SSL/TLS 加密**：支持内置 SSL 配置，为 Web 访问及 RSS 流量提供原生加密保障。
- **📈 YouTube API 用量洞察**：在同步触及限额前监控配额使用情况。
- **🔄 OPML 订阅导出**：轻松导出订阅，便于迁移到其他播客客户端。
- **⬆️ 应用内 yt-dlp 管理**：无需离开应用即可管理运行时、切换生效版本并更新 yt-dlp。
- **🛠 高级 yt-dlp 参数**：通过自定义 yt-dlp 参数精细调整下载行为。
- **📚 Podcasting 2.0 章节支持**：生成章节文件，带来更丰富的播放导航。
- **🌐 多语言自适应界面**：支持八种界面语言，桌面和移动端均可流畅使用。

## 快速开始

推荐使用 Docker Compose 部署：

```yml
services:
  pigeon-pod:
    build: .
    image: 'pigeon-pod:latest'
    restart: unless-stopped
    container_name: pigeon-pod
    ports:
      - '8834:8080'
    environment:
      - SPRING_DATASOURCE_URL=jdbc:sqlite:/data/pigeon-pod.db
      # 可选：只有在你已使用其他可信认证层保护实例时，才关闭内置认证
      # - PIGEON_AUTH_ENABLED=false
    volumes:
      - data:/data

volumes:
  data:
```

启动：

```bash
docker compose up -d
```

访问：

```text
http://localhost:8834
```

默认账号：

- 用户名：`root`
- 密码：`Root@123`

> [!WARNING]
> `PIGEON_AUTH_ENABLED` 默认值为 `true`。只有在已有其他可信保护层守护 Web UI 时，例如 auth proxy、反向代理访问控制、VPN 或私有网络，才应将其设置为 `false`。
>
> 不要将关闭认证的实例直接暴露在公网。

## 文档入口

- [英文主 README](README.md)
- 设计与架构文档：仓库中的 `dev-docs/`

## 补充说明

- 当前推荐部署方式是 Docker，不再推荐直接运行 JAR。
- 如果你只是想快速判断项目是否适合自己，先看本页即可。
- 如果你要做更深的定制、开发或架构理解，请回到仓库中的 `dev-docs/`。

---

<div align="center">
  <p>为播客爱好者用 ❤️ 制作！</p>
</div>
