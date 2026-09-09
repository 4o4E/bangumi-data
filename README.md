# bangumi-data

一个面向多个消费者的 Bangumi 数据服务，负责上游数据采集、规范化、派生分析和稳定查询。neko-bot 的 Wife 功能将作为首个消费者，但项目边界不会包含抽妻、每日缓存或卡片绘制等 Bot 业务。

## 当前阶段

仓库目前处于架构选择阶段，只固定了 Kotlin 与 Gradle Kotlin DSL。数据库、模块拆分、同步进程、API 形式和发布方式尚未写死，避免在用户完成方案反选前形成事实上的架构决定。

- 社区案例调研：[`docs/research/community-cases.md`](docs/research/community-cases.md)
- 分项方案与反选表：[`docs/architecture-options.md`](docs/architecture-options.md)
- 决策记录说明：[`docs/decisions/README.md`](docs/decisions/README.md)

## 预期边界

```text
Bangumi Archive ─┐
                 ├─> 采集/导入进程 ─> 服务私有数据存储 ─> 稳定查询 API
Bangumi v0 API ──┘                                      ├─> neko-bot Wife
                                                       └─> 未来其他服务
```

核心目标是让上游采集策略可以独立演进，同时让消费者只随稳定契约升级，而不感知数据库结构和抓取实现。

## 构建基线

- Kotlin `2.2.21`
- Gradle Kotlin DSL
- 建议 JDK 17；最终运行基线待服务框架选择后写入构建约束
