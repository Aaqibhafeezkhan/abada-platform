# The CRM showcase worker: the Abada Java SDK plus one source file.
# Build context: the repository root (the SDK is built from sdk/java).
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY engine/.mvn .mvn
COPY engine/mvnw mvnw
COPY sdk/java sdk/java
RUN ./mvnw -q -f sdk/java/pom.xml -DskipTests package dependency:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory=/build/lib \
    && cp sdk/java/target/abada-worker-client-*.jar /build/lib/

# A JDK, not a JRE: the worker runs as a single-file source program.
FROM eclipse-temurin:21-jdk-alpine
ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
RUN addgroup -S abada && adduser -S -G abada abada
WORKDIR /app
COPY --from=build /build/lib /app/lib
COPY examples/showcase/crm-leads/crm-worker/CrmWorker.java /app/CrmWorker.java
USER abada
ENTRYPOINT ["java", "-cp", "/app/lib/*", "/app/CrmWorker.java"]
