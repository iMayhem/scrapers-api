FROM gradle:9.4.1-jdk21 AS build

WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common common
COPY android-stubs android-stubs
COPY plugin-runtime plugin-runtime
COPY library library
COPY server server

RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/server/build/install/server /app

# Pull the latest scrapers from the phisher repo at image build time
COPY scripts scripts
COPY config.json config.json
RUN chmod +x scripts/update-plugins.sh && ./scripts/update-plugins.sh

EXPOSE 8080

CMD ["/app/bin/server"]