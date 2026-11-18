FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/evils-ebics-server-*.jar app.jar
EXPOSE 8080
# Ohne dies zeigte "docker compose ps"/ein Orchestrator fuer diesen Service nie eine
# Bereitschaft an (kein Healthcheck ueberhaupt), damit der Container erst als gesund gilt,
# wenn die Anwendung antwortet.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
