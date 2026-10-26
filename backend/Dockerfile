# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
# 国内网络可传入 --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public
ARG MAVEN_MIRROR=""
WORKDIR /src
RUN if [ -n "$MAVEN_MIRROR" ]; then \
      mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi
# 先复制 pom 并下载依赖，源码变化时可复用这一层缓存
COPY pom.xml .
COPY clinic-common/pom.xml clinic-common/
COPY clinic-framework/pom.xml clinic-framework/
COPY clinic-server/pom.xml clinic-server/
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp -q dependency:go-offline -DexcludeGroupIds=io.github.ryancamus || true
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -ntp -DskipTests package

# ---------- 运行阶段 ----------
# Alpine 版 JRE：镜像更小，也不带 Ubuntu 版里与运行无关的工具（如 pebble）带来的漏洞；构建时顺带打上系统补丁
FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && addgroup -S -g 1001 clinic && adduser -S -u 1001 -G clinic clinic
WORKDIR /app
COPY --from=build /src/clinic-server/target/campus-clinic-his.jar app.jar
USER clinic
EXPOSE 8080
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
