# ============================================================
# gulimall-k8s 运行镜像
# ------------------------------------------------------------
# Jenkins 先执行 mvn clean package，再把 target 下的 jar 打进镜像。
# 使用 JRE 21，不把整套 JDK 带进运行镜像。
# ============================================================

FROM eclipse-temurin:21-jre-jammy

# 1. 创建固定 uid/gid 的非 root 用户，和 Deployment 的 securityContext 对齐
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --system --home-dir /app --shell /usr/sbin/nologin app

# 2. 工作目录
WORKDIR /app

# 3. 复制 Jenkins 已经打好的 jar
COPY target/gulimall-k8s-1.0.0.jar /app/app.jar

# 4. 修改属主
RUN chown -R app:app /app

# 5. 切换非 root 用户
USER 10001:10001

# 6. 服务端口
EXPOSE 18080

# 7. 时区和 JVM 容器参数
ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -Xss512k -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

# 8. 启动
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
