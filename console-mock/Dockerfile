# 설비 콘솔 목업. 빌드 컨텍스트는 저장소 루트다(멀티모듈 settings.gradle.kts).
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY backend backend
COPY console-mock console-mock
RUN ./gradlew --no-daemon :console-mock:bootJar

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/console-mock/build/libs/console-mock-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
