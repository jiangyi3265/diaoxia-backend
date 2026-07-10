FROM maven:3.9.11-eclipse-temurin-17 AS builder
WORKDIR /source
COPY . .
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data/diaoxia/upload \
    && chown -R app:app /app /data/diaoxia
COPY --from=builder /source/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-Djava.security.egd=file:/dev/./urandom","-jar","/app/app.jar"]
