# ADR 0003：API、鉴权与镜像发布

- 状态：已接受
- 日期：2026-09-09

## 决策

- 对外提供 `/api/v1` REST API 和 OpenAPI 文档。
- 同仓库维护 `api-model` 与手写 Ktor `http-client`，供 neko-bot 和其他 Kotlin 服务使用。
- `BANGUMI_DATA_TOKEN` 是业务接口的唯一访问 token，只能通过环境变量注入，不存入数据库和磁盘配置。
- `GET /health` 只用于容器探活并免鉴权；全部 `/api/v1/**` 接口要求 Bearer token。
- 服务镜像发布到 `ghcr.io/<owner>/bangumi-data`，不发布 DockerHub 镜像。

## 兼容性

服务端在 `/api/v1` 内保持向后兼容；破坏性修改必须新增 API 主版本。客户端和服务镜像独立版本化，不要求消费者与服务端同时部署。
