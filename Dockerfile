FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN useradd --system --uid 10001 --create-home bangumi
COPY --chown=bangumi:bangumi build/docker/app /app
RUN chmod +x /app/bin/bangumi-data

USER bangumi
EXPOSE 8080
ENTRYPOINT ["/app/bin/bangumi-data"]
