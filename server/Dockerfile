# kcgl-server 多阶段构建：构建层用 Maven+JDK21，运行层仅 JRE（生产镜像 kcgl-app）
FROM maven:3.9-eclipse-temurin-21 AS build
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
# /data 由 compose 命名卷挂载（kcgl-app-data）。Docker 首次使用空卷时，会把镜像里挂载点
# 的内容**连属主一起**复制进卷——所以目录必须在这里建好并 chown 给 app：否则卷根是
# root:root、运行时 app(uid 999) 无写权，**照片上传与 Excel/Yahoo 导入会全数失败**
# （D-089 干净主机演练实测：上传 500「画像ファイルの保存に失敗しました」，日志无因——
# 本地开发与 E2E 跑的是宿主 jar、目录由宿主用户创建，照不出这个洞）。
RUN mkdir -p /data/images /data/imports/yahoo /data/imports/excel \
    && chown -R app:app /data
COPY --from=build /build/target/*.jar app.jar
USER app
ENV TZ=Asia/Tokyo \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
