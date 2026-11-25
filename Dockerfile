# MeterFlow Container Build (Maven 3.9 + Java 25 Temurin Builder -> Java 25 Temurin JRE Runtime)
FROM maven:3.9-eclipse-temurin-25-alpine AS builder
WORKDIR /workspace

# Route Maven Central through Google's official public mirror inside container builds
# to avoid shared-IP HTTP 429 rate limits on cloud build runners (e.g., Render).
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<settings>' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>google-maven-central</id>' \
    '      <name>Google Maven Central Mirror</name>' \
    '      <url>https://maven-central.storage-download.googleapis.com/maven2</url>' \
    '      <mirrorOf>central</mirrorOf>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' \
    > /root/.m2/settings.xml

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ src/
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S meterflow && adduser -S meterflow -G meterflow
USER meterflow:meterflow

COPY --from=builder /workspace/target/meterflow-*.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
