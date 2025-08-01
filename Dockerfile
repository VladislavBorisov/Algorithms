FROM openjdk:17-jre-slim

# Create app directory
WORKDIR /app

# Copy the jar file
COPY target/config-server-1.0.0.jar app.jar

# Create logs directory
RUN mkdir -p logs

# Expose the port
EXPOSE 8888

# Add health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8888/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]