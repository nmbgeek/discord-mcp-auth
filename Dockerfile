FROM maven:3.9.16-amazoncorretto-17@sha256:046784a9c83821fc3e9ded94c2f18155e0daf1981c8a0813d0ff693a9dc4a51b AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn --batch-mode --no-transfer-progress clean package

FROM amazoncorretto:17-alpine@sha256:80d268d89c10861f33792c7845bbd7c8494678a600f031988e135fe27eae3b48

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8085

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8085/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
