FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
# 镜像默认按生产运行；本地无 profile 启动时 application.yml 会回落到 dev
ENV SPRING_PROFILES_ACTIVE=prod
RUN useradd --system --uid 10001 selfmusic
COPY --from=build /workspace/target/selfmusic-server-0.1.0.jar app.jar
USER selfmusic
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
