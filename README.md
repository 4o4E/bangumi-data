# bangumi-data

一个面向多个消费者的 Bangumi 数据服务，负责上游数据采集、规范化、派生分析和稳定查询。neko-bot 的 Wife 功能将作为首个消费者，但项目边界不会包含抽妻、每日缓存或卡片绘制等 Bot 业务。

## 已确认架构

项目采用单一 Kotlin/Ktor 后端进程，同时提供查询 API、定时 Archive 全量同步和 Bangumi v0 热数据刷新。数据保存在 PostgreSQL，消费者只依赖版本化 HTTP API 和 Kotlin 客户端。

- 只保存当前实体、关系、热度和熟悉度结果，不采集热度历史。
- 全量导入使用数据代切换，旧代只在切换和回收期间短暂存在。
- 所有业务接口使用 `BANGUMI_DATA_TOKEN` Bearer token；token 不写入数据库或配置文件。
- 服务镜像发布到 GitHub Container Registry（GHCR）。

当前提交已经建立模块、鉴权、健康检查、客户端骨架、容器和发布流水线；PostgreSQL schema、Archive 导入器、v0 刷新和熟悉度算法将在后续实现。

- 社区案例调研：[`docs/research/community-cases.md`](docs/research/community-cases.md)
- 分项方案与反选表：[`docs/architecture-options.md`](docs/architecture-options.md)
- 决策记录说明：[`docs/decisions/README.md`](docs/decisions/README.md)

## 系统边界

```text
Bangumi Archive ─┐
                 ├─> bangumi-data 单后端 ─> PostgreSQL 当前快照
Bangumi v0 API ──┘              └───────> 稳定查询 API
                                              ├─> neko-bot Wife
                                              └─> 未来其他服务
```

核心目标是让上游采集策略可以独立演进，同时让消费者只随稳定契约升级，而不感知数据库结构和抓取实现。

## 模块

- `api-model`：稳定 DTO 和错误模型。
- `http-client`：供 Kotlin 消费者使用的 Ktor 客户端。
- `server`：唯一后端，包含 API、采集调度、领域算法和 PostgreSQL 存储。

模块是编译边界，不是独立进程或独立服务。

## 配置

| 环境变量 | 必需 | 说明 |
| --- | --- | --- |
| `BANGUMI_DATA_TOKEN` | 是 | 至少 32 字符，保护全部 `/api/v1/**` 业务接口 |
| `BANGUMI_DATA_HOST` | 否 | 默认 `0.0.0.0` |
| `BANGUMI_DATA_PORT` | 否 | 默认 `8080` |
| `BANGUMI_DATA_DATABASE_URL` | 是 | PostgreSQL JDBC URL |
| `BANGUMI_DATA_DATABASE_USER` | 是 | PostgreSQL 用户名 |
| `BANGUMI_DATA_DATABASE_PASSWORD` | 是 | PostgreSQL 密码 |
| `BANGUMI_DATA_SYNC_ENABLED` | 否 | 默认 `true`，是否运行进程内采集调度 |
| `BANGUMI_DATA_SYNC_INTERVAL_HOURS` | 否 | 默认每 6 小时检查 Archive 新版本 |
| `BANGUMI_DATA_REQUEST_DELAY_MS` | 否 | Bangumi v0 详情请求的最小间隔，默认 250ms |
| `BANGUMI_DATA_DIRECTORY` | 否 | 下载临时目录，默认 `data` |
| `BANGUMI_DATA_SYNC_ENABLED` | 否 | 默认 `true`，在同一后端内启用定时同步 |
| `BANGUMI_DATA_SYNC_INTERVAL_HOURS` | 否 | 默认每 6 小时检查 Archive 版本 |
| `BANGUMI_DATA_REQUEST_DELAY_MS` | 否 | Bangumi v0 请求间隔，默认 250ms |
| `BANGUMI_DATA_DIRECTORY` | 否 | Archive 临时下载目录，默认 `data` |

`GET /health` 不返回业务数据，保留为免鉴权的容器健康检查；其他接口必须发送 `Authorization: Bearer <token>`。

## 数据同步

服务首次启动后会在后台读取官方 Archive：只导入动画、游戏、角色和完整作品—角色关系，过滤 NSFW、儿童向及缺少中文作品名的条目。熟悉度由每部作品内角色 `collects` 的 log 分布、三段自然聚类和边界相近值计算，不使用固定作品名单，也不直接按主角/配角截断。最终候选再通过 Bangumi v0 补齐性别、中文名、别名和图片。

可用带 token 的管理接口立即触发检查或强制重建：

```text
POST /api/v1/admin/sync
POST /api/v1/admin/sync?force=true
```

查询接口：

```text
GET /api/v1/catalog/status
GET /api/v1/catalog/characters?gender=FEMALE&tiers=CORE,FAMILIAR&offset=0&limit=500
GET /openapi.yaml
```

当前接口：

- `GET /openapi.yaml`：OpenAPI 3.1 契约。
- `GET /api/v1/catalog/status`：活动数据代、来源版本和同步状态。
- `GET /api/v1/catalog/characters`：按性别和熟悉度分页获取完整角色卡片数据。
- `POST /api/v1/admin/sync`：手动触发后台同步，可选 `force=true`。

## Compose 部署

复制 `.env.example` 为 `.env` 并填写 token 和 PostgreSQL 密码后，Compose 会直接使用已发布的 GHCR 镜像，不会在部署机本地构建：

```powershell
docker compose pull
docker compose up -d
```

当前固定版本为 `ghcr.io/4o4e/bangumi-data:v0.1.2`；升级时先修改 `docker-compose.yml` 中的镜像标签，再重新执行上述命令。

## 构建

- Kotlin `2.2.21`
- Gradle Kotlin DSL
- JDK 11 或更高版本；生产镜像使用 JRE 17

```powershell
.\gradlew.bat test
```

本地构建镜像前先生成运行目录，Dockerfile 本身不在容器内重复下载 Gradle 和 Maven 依赖：

```powershell
.\gradlew.bat prepareDockerContext
docker build -t bangumi-data:local .
```

## 发布

推送带 `v` 前缀的 SemVer 标签后，GitHub Actions 会运行测试、构建 `linux/amd64` 与 `linux/arm64` 镜像、发布到 GHCR，并创建同名 GitHub Release：

```powershell
git tag v0.1.2
git push origin v0.1.2
```

发布产物：

```text
ghcr.io/4o4e/bangumi-data:v0.1.2
ghcr.io/4o4e/bangumi-data:latest
```
