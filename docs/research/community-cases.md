# Bangumi 数据采集社区案例调研

调研日期：2026-09-09。本文只总结可复用的设计信号，不复制第三方实现。

## 1. 官方 Bangumi Archive

仓库：<https://github.com/bangumi/Archive>

这是目前最适合作为本项目全量基线的数据源：官方说明其目标是提供非实时数据、减少爬虫压力，按周三北京时间 05:00 左右发布 JSON Lines 压缩包。归档覆盖作品、人物、角色、章节，以及作品—角色、作品—人物、人物—角色和其他关系。

可直接支持的通用能力：

- `subjects` 同时覆盖书籍、动画、音乐、游戏和三次元作品；保存中文名、原名、平台、简介、标签、收藏、评分、排名、NSFW 等字段。
- `subject-characters` 保存角色在每部作品中的关系，能区分主角、配角和客串，不能再把关系压平为角色的作品 ID 数组。
- `characters` 提供角色收藏数，可作为角色热度的原始指标，但派生层必须保留指标名称和时间。
- JSON Lines 适合流式导入，不必把数百 MiB 的压缩包整体解压到内存。

限制：

- 周级数据不能承担新条目即时更新。
- Archive 仓库当前未提供 GitHub 可识别的许可证文件；公开再分发归档数据前需要单独确认数据使用与署名条件。
- 归档是数据源，不是给消费者直接依赖的稳定业务 API。

结论：适合“全量基线”，不应单独承担所有增量更新。

## 2. 官方 Bangumi v0 API

仓库与定义：<https://github.com/bangumi/api>、<https://github.com/bangumi/api/blob/master/open-api/v0.yaml>

新版 `/v0` API 是官方维护边界，覆盖作品、角色、人物、章节、关系与搜索。作品类型包括书籍、动画、音乐、游戏和三次元。OpenAPI 将搜索接口标记为实验能力，因此搜索适合发现近期候选，实体详情和关系接口才适合校验、刷新真值。

接入要求：

- 服务必须设置可识别的 `User-Agent`，包含开发者标识和应用名；开源应用应附主页，发布后附版本。规则见 <https://github.com/bangumi/api/blob/master/docs-raw/user%20agent.md>。
- 尊重接口缓存周期和限流；本地也要做请求合并、退避与失败续跑。
- API 返回的数据先进入来源模型，再规范化，避免上游字段直接泄漏进公共契约。

结论：适合“热数据补充、按需刷新与详情校验”，不适合单靠遍历完成全量基线。

## 3. nz3u/bangumi-data

仓库：<https://github.com/nz3u/bangumi-data>，许可证：AGPL-3.0。

这是与目标最接近的社区服务案例。其思路是下载最新 Archive 压缩包，直接流式读取其中的 JSON Lines，构建临时 SQLite 数据库，完成完整性检查和索引后再原子替换线上数据库；对外提供 REST API、OpenAPI/Swagger、全文搜索和标签反向查询。

值得吸收：

- “构建新代数据，再切换活动版本”，避免消费者读到半导入状态。
- 批量导入完成后再建索引，显著降低全量更新成本。
- 同步期有明确维护状态，失败不会破坏上一代可用数据。
- API 契约与导入实现隔离。

不直接照搬：

- SQLite 文件替换非常适合单机只读部署，但多实例 API、独立 worker 和并发增量更新更适合 PostgreSQL 的代际切换。
- 代码为 AGPL-3.0；在项目许可证确定前仅借鉴架构，不复制实现。

## 4. bangumi/server

仓库：<https://github.com/bangumi/server>，许可证：AGPL-3.0。

官方服务端采用 Go，并组合 MySQL、Redis、Kafka、Meilisearch。它适合用于确认领域常量、官方行为和大型部署中的职责拆分，但整套基础设施超出当前项目规模，不应作为首版部署模板。

可借鉴：领域对象边界、关系建模、独立搜索索引。暂不借鉴：Kafka 驱动的完整生产拓扑。

## 5. VaillerTeeter/Bangumi-api-client

仓库：<https://github.com/VaillerTeeter/Bangumi-api-client>，许可证：GPL-3.0。

这是 TypeScript 的 v0 类型客户端，覆盖大量端点并包含集成测试。它不能直接给 Kotlin 使用，但证明了两件事：客户端应围绕 OpenAPI 能力面维护；端点覆盖矩阵和真实 API 集成测试应独立存在。

结论：借鉴测试矩阵，不引入其语言栈或复制 GPL 实现。

## 6. UnoUzume/Bangumi-Database

仓库：<https://github.com/UnoUzume/Bangumi-Database>

这是较早的抓取与数据库项目，最有价值的信号是：作品、角色、人物之间必须使用规范化多对多关系表，不能把关系塞进单个 JSON 数组。其采集方式依赖旧页面和旧接口，不适合作为新项目的数据入口。

## 综合结论

推荐组合不是“选一个社区项目重写”，而是：

1. 用官方 Archive 建立周期性完整基线。
2. 用官方 v0 API 做近期与按需刷新。
3. 借鉴 `nz3u/bangumi-data` 的代际导入和原子切换。
4. 借鉴官方服务端的领域边界，但保持首版基础设施轻量。
5. 自己定义稳定的 Kotlin DTO、REST API 和客户端，避免消费者依赖上游结构。
