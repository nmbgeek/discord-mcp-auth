FROM docker.io/library/maven:3.9.16-amazoncorretto-17@sha256:046784a9c83821fc3e9ded94c2f18155e0daf1981c8a0813d0ff693a9dc4a51b AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn --batch-mode --no-transfer-progress clean package

FROM docker.io/library/amazoncorretto:17-alpine@sha256:80d268d89c10861f33792c7845bbd7c8494678a600f031988e135fe27eae3b48

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# Stable ownership for logs and persistent OAuth storage, including fresh named volumes.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
    && mkdir -p /app/target/logs /app/data/oauth \
    && chown -R app:app /app/target /app/data
USER app

EXPOSE 8085

# HTTP health checks are defined in docker-compose.yml; stdio has no HTTP endpoint.

ENTRYPOINT ["java", "-jar", "app.jar"]
