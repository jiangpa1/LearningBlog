# ══════════════════════════════════════════════════════════════════════
#  阶段一：构建 —— 用带 Maven 的镜像编译打包
# ══════════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只拷 pom 并单独下载依赖：
# 只要 pom.xml 没变，这一层就命中缓存 —— 之后改 Java 代码不会重新拉依赖，构建快很多
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# 再拷源码打包。
# 跳过测试：单元测试属于「提交前在宿主机跑」的环节，镜像构建只负责产出可运行产物，
# 不该被测试拖慢（也不该让镜像构建因为测试失败而失败）
COPY src ./src
RUN mvn -B -q -DskipTests package

# ══════════════════════════════════════════════════════════════════════
#  阶段二：运行 —— 只带 JRE
#  源码、Maven、~/.m2 一律不进最终镜像（多阶段构建的意义就在这里）
# ══════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:17-jre

WORKDIR /app

# 容器默认时区是 UTC。不设的话日志时间与 create_time 会比北京时间差 8 小时
ENV TZ=Asia/Shanghai

# 不用 root 跑应用：万一被攻破，攻击者拿到的是普通用户，而不是容器内的 root
RUN groupadd -r app && useradd -r -g app -m app

# ★ 只从构建阶段取一个 jar 出来
COPY --from=build --chown=app:app /build/target/*.jar app.jar

USER app

EXPOSE 8081

# 用 exec 形式（JSON 数组）而不是 shell 形式：
# 这样 java 进程就是 PID 1，能直接收到 docker stop 的 SIGTERM，
# Spring 的优雅停机才会生效；shell 形式下信号会被 sh 吞掉
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
