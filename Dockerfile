# Stage 1: Build the application using Maven
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /app


# Copy pom.xml and download dependencies for caching
COPY pom.xml .
RUN mvn dependency:go-offline --no-transfer-progress


# Copy source code and build jar
COPY src ./src
RUN mvn clean package -DskipTests


# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine

# Set non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app


# Copy the built jar from the build stage
# Copy the built jar from the build stage
COPY --from=build /app/target/minio-attempt-*.jar app.jar


# Expose port 8080
EXPOSE 8080



# Command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]