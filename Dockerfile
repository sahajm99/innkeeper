# Stage 1: build the executable jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/innkeeper-*.jar extract --destination extracted

# Stage 2: a small runtime with an AppCDS archive made by a training run.
FROM eclipse-temurin:21-jre
ARG GIT_COMMIT=unknown
ENV GIT_COMMIT=${GIT_COMMIT}
RUN useradd --system --uid 10001 --home /app innkeeper \
 && mkdir -p /app/data && chown -R innkeeper /app
WORKDIR /app
COPY --from=build --chown=innkeeper /build/extracted/ /app/
# The training run boots the demo profile against an in-memory database, runs the migrations,
# then exits once the context has refreshed, leaving a class-data-sharing archive behind.
RUN mv /app/innkeeper-*.jar /app/innkeeper.jar \
 && java -XX:ArchiveClassesAtExit=/app/app.jsa -Dspring.context.exit=onRefresh \
      -Dspring.profiles.active=demo \
      "-Dspring.datasource.url=jdbc:h2:mem:cds;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" \
      -jar /app/innkeeper.jar \
 && chown innkeeper /app/app.jsa
USER innkeeper
ENV SPRING_PROFILES_ACTIVE=demo
ENV JAVA_TOOL_OPTIONS="-XX:SharedArchiveFile=/app/app.jsa -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:MaxRAMPercentage=60 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/innkeeper.jar"]
