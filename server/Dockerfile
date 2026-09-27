# kcgl-server 多阶段构建：构建层用 Maven+JDK21，运行层仅 JRE（生产镜像 kcgl-app）
FROM maven:3-eclipse-temurin-24 AS build
WORKDIR /build
# 先单独拷贝 pom 拉依赖，利用层缓存
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app app
COPY --from=build /build/target/*.jar app.jar
USER app
ENV TZ=Asia/Tokyo \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
