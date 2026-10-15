# api·worker 공용 이미지(한 jar, 역할은 --mode). 빌드 컨텍스트는 저장소 루트다(멀티모듈 settings.gradle.kts).
# 런타임 베이스는 Playwright Java 1.62.0 이미지 — 크로미엄이 코드의 playwright 버전과 같은 리비전으로 들어 있다.
# 비밀값은 이미지에 굽지 않는다 — DB·시드 비밀번호는 compose가 런타임 env로 준다(deploy-gates.sh ③).
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY backend backend
COPY console-mock console-mock
RUN ./gradlew --no-daemon :backend:bootJar

FROM mcr.microsoft.com/playwright/java:v1.62.0-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/backend/build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
