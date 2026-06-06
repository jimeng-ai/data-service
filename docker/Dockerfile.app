# data-service 应用运行时镜像（gateway / data-server 共用）。
# jar 由 CI 在宿主用 mvn 构建（self-hosted Runner 已装 JDK17/Maven），这里只 COPY，
# 不在镜像里重跑整个 Maven reactor。用 build-arg JAR 指定要装进来的可执行 jar。
#   docker build -f docker/Dockerfile.app --build-arg JAR=gateway/target/xxx.jar -t ds-gateway:latest .
FROM eclipse-temurin:17-jre
WORKDIR /app
ARG JAR
COPY ${JAR} /app/app.jar
# data-server 的 skill 能力(rag_search 等)从文件系统 ./skills(相对 cwd=/app) 加载，
# 必须把仓库内 skills 目录打进镜像；否则 prod 容器 tools 为空、模型只能在文本里假装调用工具。
# gateway 不读 skills，多拷无害(二者共用此 Dockerfile)。
COPY modules/data-server/skills /app/skills
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
