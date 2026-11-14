# ----- Stage 1: Build the app with Maven -----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
# Download dependencies first (cached layer)
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests -B

# ----- Stage 2: Run with a minimal JRE image -----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
