# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY frontend/package*.json frontend/
COPY frontend/index.html frontend/
COPY frontend/vite.config.ts frontend/
COPY frontend/tsconfig*.json frontend/
COPY src ./src
COPY frontend/src ./frontend/src
COPY frontend/public ./frontend/public
RUN mvn -B clean package -DskipTests

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/data
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
