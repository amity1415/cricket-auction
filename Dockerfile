# ---- Build stage: compile and package the Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the Maven wrapper + pom first so dependency resolution is cached
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
# Now copy sources and build (tests skipped — they run in dev/CI)
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

# ---- Run stage: slim JRE with just the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/cricket-auction-0.1.0.jar app.jar
# Railway injects $PORT at runtime; the app reads it (see application.yml).
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
