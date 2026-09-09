FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

LABEL org.opencontainers.image.source="https://github.com/4o4E/bangumi-data"
LABEL org.opencontainers.image.description="通用 Bangumi 数据采集、规范化与查询服务"

RUN useradd --system --uid 10001 --create-home bangumi
COPY --chown=bangumi:bangumi build/docker/app /app
RUN chmod +x /app/bin/bangumi-data

USER bangumi
EXPOSE 8080
ENTRYPOINT ["/app/bin/bangumi-data"]
