# Use a base image with Java 17 (Eclipse Temurin is a popular choice)
FROM eclipse-temurin:17-jre-jammy

# Install FFmpeg and fonts for Chinese support
RUN apt-get update && \
    apt-get install -y ffmpeg fonts-noto-cjk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the built jar file
# Ensure you run 'mvn clean package' before building the docker image
COPY target/video-process-0.0.1-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
