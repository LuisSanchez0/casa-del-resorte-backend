# ========================================
# ETAPA 1 - Build
# ========================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw

RUN ./mvnw dependency:go-offline -B

COPY src ./src

RUN ./mvnw clean package -DskipTests


# ========================================
# ETAPA 2 - Runtime
# ========================================
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/target/spring-house-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]