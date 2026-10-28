# The inbox core: Quarkus on a JRE, run as a non-root user.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew quarkusBuild -x test --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home inbox
WORKDIR /app
COPY --from=build --chown=inbox /src/build/quarkus-app/ ./
USER inbox
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
HEALTHCHECK --interval=10s --timeout=3s CMD curl -sf localhost:8080/q/health/ready || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
