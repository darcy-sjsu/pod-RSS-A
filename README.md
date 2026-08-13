# PigeonPod

自托管的 YouTube 转播客桥接服务。可将 YouTube 频道与播放列表转换为标准 RSS，并按规则自动同步与下载。

## 技术栈

### 后端
- **Java 17** - 核心语言
- **Spring Boot 3.5** - 应用框架
- **MyBatis-Plus 3.5** - ORM 框架
- **Sa-Token** - 认证框架
- **SQLite** - 轻量数据库
- **Flyway** - 数据库迁移工具
- **YouTube Data API v3** - YouTube 数据获取
- **yt-dlp** - 视频下载工具
- **Rome** - RSS 生成库

### 前端
- **Javascript (ES2024)** - 核心语言
- **React 19** - 应用框架
- **Vite 7** - 构建工具
- **Mantine 8** - UI 组件库
- **i18next** - 国际化
- **Axios** - HTTP 客户端

## 环境依赖

- Docker 与 Docker Compose（推荐部署方式）
- 或使用 JAR / 本地开发时需要：
  - Java 17+
  - Node.js 22+
  - Maven 3.9+
  - SQLite
  - yt-dlp
  - FFmpeg
  - Deno 2.3+（当前 yt-dlp YouTube 提取所需）

## 部署

### 使用 Docker Compose（推荐）

**请先在本机安装 Docker 与 Docker Compose。**

本仓库是修改后的源码。部署规则：

- **本地项目必须构建**：`pigeon-pod` 只用当前仓库的 `Dockerfile` 本地构建，不拉取远程应用镜像，也不要使用上游官方镜像 `ghcr.io/aizhimou/pigeon-pod:latest`。
- **依赖可以拉取公开镜像**：`bgutil-provider` 使用公开镜像 `brainicism/bgutil-ytdlp-pot-provider`，这是 YouTube PO Token 依赖。

`docker-compose.yml` 不为应用服务设置 `image:`，并使用 `pull_policy: build` 与 `build.pull: false`，避免 Compose 先去远程仓库拉应用镜像。

1. 克隆本仓库，并进入仓库根目录（此处有 `Dockerfile` 与 `docker-compose.yml`）
```bash
git clone https://github.com/darcy-sjsu/pod-RSS-A.git
cd pod-RSS-A
```

2. 按需修改仓库根目录的 `docker-compose.yml` 环境变量。当前配置如下：
```yml
# Local app: must be built from this repository. Do not pull a remote pigeon-pod image.
# Dependencies such as bgutil-provider may pull public images.
services:
  pigeon-pod:
    build:
      context: .
      dockerfile: Dockerfile
      pull: false
    pull_policy: build
    restart: unless-stopped
    container_name: pod-RSS-A
    ports:
      - '8586:8080'
    environment:
      - SPRING_DATASOURCE_URL=jdbc:sqlite:/data/pigeon-pod.db # set to your database path
      - PIGEON_LOG_FILE=/data/logs/pigeon-pod.log
      - PIGEON_YT_DLP_PO_TOKEN_PROVIDER_URL=http://bgutil-provider:4416
      # Optional: disable PigeonPod built-in auth when running behind another auth layer
      # - PIGEON_AUTH_ENABLED=false
    depends_on:
      - bgutil-provider
    volumes:
      - pigeon-pod-data:/data

  bgutil-provider:
    # YouTube PO Token helper: public image is allowed.
    image: brainicism/bgutil-ytdlp-pot-provider:1.3.1-deno
    restart: unless-stopped

volumes:
  pigeon-pod-data:
```

> [!WARNING]
> `PIGEON_AUTH_ENABLED` 默认值为 `true`。只有在已有其他可信保护层守护 Web UI 时，例如 auth proxy、反向代理访问控制、VPN 或私有网络，才应将其设置为 `false`。
>
> 如果关闭内置认证，必须通过其他方式保护 PigeonPod。不要将关闭认证的实例直接暴露在公网。

3. 在仓库根目录构建并启动
```bash
docker compose up -d --build
```

4. 访问应用
浏览器打开 `http://localhost:8586`，默认用户名：`root`，默认密码：`Root@123`

### 使用 JAR 运行

**请先在本机安装 Java 17+、yt-dlp、FFmpeg 与 Deno 2.3+。**

针对当前 YouTube 播放限制，请安装受支持的 PO Token provider 插件；使用 HTTP provider 时需配置 `PIGEON_YT_DLP_PO_TOKEN_PROVIDER_URL`。

1. 从源码构建 JAR，参见 [本地开发](#本地开发)

2. 在 JAR 同级目录创建 data 目录
```bash
mkdir -p data
```

3. 运行应用
```bash
java -jar -Dspring.datasource.url=jdbc:sqlite:/path/to/your/pigeon-pod.db \  # set to your database path
           pigeon-pod-x.x.x.jar
```

4. 访问应用
浏览器打开 `http://localhost:8080`，默认用户名：`root`，默认密码：`Root@123`

## 存储配置

- PigeonPod 支持 `LOCAL` 与 `S3` 两种存储模式。
- 同一时间只能启用一种模式。
- S3 模式支持 MinIO、Cloudflare R2、AWS S3 及其他 S3 兼容服务。
- 切换存储模式不会自动迁移历史媒体文件，需要手动迁移。

### 存储方式对比

| 模式 | 优点 | 缺点 |
| --- | --- | --- |
| `LOCAL` | 配置简单，无外部依赖 | 占用本地磁盘，扩展较难 |
| `S3` | 扩展性更好，适合云部署 | 需要对象存储与凭据配置 |

## 本地开发

1. 进入项目目录
```bash
cd pigeon-pod
```

2. 配置数据库与存储路径
```bash
# Create data directory
mkdir -p data/audio data/video data/cover

# The default runtime paths target /data for containers. For local development:
export PIGEON_AUDIO_FILE_PATH="$PWD/data/audio/"
export PIGEON_VIDEO_FILE_PATH="$PWD/data/video/"
export PIGEON_COVER_FILE_PATH="$PWD/data/cover/"

# Database file will be created automatically on first startup
```

3. 配置 YouTube API
   - 在 [Google Cloud Console](https://console.cloud.google.com/) 创建项目
   - 启用 YouTube Data API v3
   - 创建 API key
   - 在用户设置中配置该 API key

4. 启动后端
```bash
cd backend
mvn spring-boot:run
```

5. 启动前端（新终端）
```bash
cd frontend
npm install
npm run dev
```

6. 访问应用
- 前端开发服务器：`http://localhost:5173`
- 后端 API：`http://localhost:8080`

## 注意事项

1. 确保 yt-dlp 已安装且可在命令行中调用
2. 配置正确的 YouTube API key
3. 确保存储目录有足够磁盘空间
4. 定期清理旧媒体文件以节省空间
5. 推荐使用 Docker Compose 部署
6. 更详细的设计与架构文档见仓库中的 `dev-docs/`
