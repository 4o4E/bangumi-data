# bangumi-data

一个面向多个消费者的 Bangumi 数据服务，负责上游数据采集、规范化、派生分析和稳定查询。neko-bot 的 Wife 功能将作为首个消费者，但项目边界不会包含抽妻、每日缓存或卡片绘制等 Bot 业务。

## 已确认架构

项目采用单一 Kotlin/Ktor 后端进程，同时提供查询 API、定时 Archive 全量同步和 Bangumi v0 热数据刷新。数据保存在 PostgreSQL，消费者只依赖版本化 HTTP API 和 Kotlin 客户端。

- 只保存当前实体、关系、热度和熟悉度结果，不采集热度历史。
- 全量导入使用数据代切换，旧代只在切换和回收期间短暂存在。
- 所有业务接口使用 `BANGUMI_DATA_TOKEN` Bearer token；token 不写入数据库或配置文件。
- 服务镜像发布到 GitHub Container Registry（GHCR）。

当前服务已包含 PostgreSQL schema、Archive 导入、Bangumi v0 补充、断点续采和版本化熟悉度算法。

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
| `HTTPS_PROXY` / `HTTP_PROXY` | 否 | Archive 下载和 Bangumi v0 请求使用的 HTTP 代理，优先读取 `HTTPS_PROXY` |

`GET /health` 不返回业务数据，保留为免鉴权的容器健康检查；其他接口必须发送 `Authorization: Bearer <token>`。

## 数据同步

服务首次启动后会在后台读取官方 Archive：只导入动画、游戏、角色和完整作品—角色关系，过滤儿童向条目；作品没有中文名时使用原名，NSFW 作品也参与关系和热度计算。熟悉关系需要同时通过作品内角色相对热度、同类型全站角色绝对热度和同类型全站作品收藏热度三层自适应分布；任一层落入长尾都会被排除。算法不使用固定作品名单、固定收藏数阈值，也不直接按主角/配角截断；版本保存在关系记录中，升级后会对当前数据代重新计算。

Archive 的作品收藏状态 `wish`、`done`、`doing`、`on_hold`、`dropped` 会原样保存，并随角色响应返回。诊断接口可以对照角色收藏数、作品总收藏、作品内排名和入选原因，区分冷门作品的头部角色与热门作品中误带出的长尾角色。旧数据代升级后会自动重读一次当前 Archive 补齐这些字段，但会复用已有角色和作品详情补充进度，不采集历史快照。

最终候选再通过 Bangumi v0 补齐性别、名称、别名和图片；没有中文角色名时使用原名。Bangumi 只提供条目级 NSFW 标记，不提供图片级安全字段，因此 NSFW 作品在没有逐图审核结论时不会向消费者下发封面，角色本身仍按角色级 NSFW 标记排除。Archive 基线与详情补充均按批提交，并通过来源摘要和 `enriched` 状态断点续采；首次同步得到第一批有效角色后即可查询，作品信息会继续在后台逐批完善。

可用带 token 的管理接口立即触发检查或强制重建：

```text
POST /api/v1/admin/sync
POST /api/v1/admin/sync?force=true
```

查询接口：

```text
GET /api/v1/catalog/status
GET /api/v1/catalog/characters?gender=FEMALE&tiers=CORE,FAMILIAR&offset=0&limit=500
GET /api/v1/admin/popularity-diagnostics?gender=FEMALE&max_character_collects=20&limit=100
GET /api/v1/admin/popularity-diagnostics?gender=FEMALE&character_id=215117
GET /openapi.yaml
```

当前接口：

- `GET /openapi.yaml`：OpenAPI 3.1 契约。
- `GET /api/v1/catalog/status`：活动数据代、来源版本和同步状态。
- `GET /api/v1/catalog/characters`：按性别和熟悉度分页获取完整角色卡片数据。
- `GET /api/v1/admin/popularity-diagnostics`：对照低收藏角色与所属作品热度，查询参数只控制诊断范围，不改变目录算法。
- `POST /api/v1/admin/sync`：手动触发后台同步，可选 `force=true`。

## Compose 部署

复制 `.env.example` 为 `.env` 并填写 token 和 PostgreSQL 密码后，Compose 会直接使用已发布的 GHCR 镜像，不会在部署机本地构建：

```powershell
docker compose pull
docker compose up -d
```

当前固定版本为 `ghcr.io/4o4e/bangumi-data:v0.1.8`；升级时先修改 `docker-compose.yml` 中的镜像标签，再重新执行上述命令。

## 构建

- Kotlin `2.2.21`
- Gradle Kotlin DSL
- JDK 11 或更高版本；生产镜像使用 JRE 17

```powershell
.\gradlew.bat test
```

本地构建镜像前先生成运行目录，Dockerfile 本身不在容器内重复下载 Gradle 和 Maven 依赖。第三方运行库与项目 jar 使用独立镜像层，后续升级只需下载变化的项目层：

```powershell
.\gradlew.bat prepareDockerContext
docker build -t bangumi-data:local .
```

## 发布

推送带 `v` 前缀的 SemVer 标签后，GitHub Actions 会运行测试、构建 `linux/amd64` 与 `linux/arm64` 镜像、发布到 GHCR，并创建同名 GitHub Release：

```powershell
git tag v0.1.6
git push origin v0.1.6
```

发布产物：

```text
ghcr.io/4o4e/bangumi-data:v0.1.6
ghcr.io/4o4e/bangumi-data:latest
```
