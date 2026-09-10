FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

LABEL org.opencontainers.image.source="https://github.com/4o4E/bangumi-data"
LABEL org.opencontainers.image.description="通用 Bangumi 数据采集、规范化与查询服务"

RUN mkdir -p /app/data \
    && chown 10001:10001 /app/data

# 第三方依赖单独成层，版本不变时客户端无需在每次发布时重新下载。
COPY --chown=10001:10001 build/docker/runtime-libs/ /app/lib/
COPY --chown=10001:10001 build/docker/application-libs/ /app/lib/

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/lib/*", "top.e404.bangumi.server.MainKt"]
