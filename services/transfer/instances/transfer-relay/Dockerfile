# Simple runtime image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# wget 설치 (healthcheck용)
RUN apk add --no-cache wget

# 로컬에서 빌드된 JAR 복사
COPY build/libs/*.jar app.jar

# JVM 옵션
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
