# syntax=docker/dockerfile:1
# Deterministic jar-copy image (no buildpack network dependency). The boot jar must be built first:
#   gradle :examples:acme-bank:<svc>:bootJar   (or `gradle bankJars` for all five)
# Alternative: `gradle :examples:acme-bank:<svc>:bootBuildImage` produces an OCI image via buildpacks.
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
ARG JAR=build/libs/*.jar
COPY ${JAR} app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
