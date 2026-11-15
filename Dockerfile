# Stage 1: Build the Spring Boot + Telegram Bot JAR using Maven
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .

# Automatically organize flat uploaded files into standard Maven folders if uploaded without folders!
RUN mkdir -p src/main/java/com/mohservice src/main/resources/static && \
    for f in *.java; do [ -f "$f" ] && mv "$f" src/main/java/com/mohservice/; done && \
    [ -f application.properties ] && mv application.properties src/main/resources/ || true && \
    [ -f index.html ] && mv index.html src/main/resources/static/ || true

RUN mvn clean package -DskipTests

# Stage 2: Lightweight Java 17 Runtime + Python 3 & Telethon for Auto Price Watcher
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip && \
    pip3 install --no-cache-dir --break-system-packages telethon && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/moh-service-bot-2.0.0.jar app.jar
COPY --from=build /app/source_watcher.py ./source_watcher.py
COPY moh_service.db* ./
RUN mkdir -p uploads && chmod -R 777 /app

EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
