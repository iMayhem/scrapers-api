FROM gradle:8.6.0-jdk21 AS build

WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
# Download dependencies beforehand if possible
RUN gradle classes --no-daemon > /dev/null 2>&1 || true

COPY src ./src
# Build the application
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/moovie-scraper-api /app

# Hugging Face Spaces expose port 7860
EXPOSE 7860

CMD ["/app/bin/moovie-scraper-api"]
