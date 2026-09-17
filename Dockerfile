# ============================================
# Build Stage (pinned Temurin 25 LTS)
# ============================================
# Tag 25.0.4_1-jdk-alpine was removed from Docker Hub; 25.0.4_7 is the
# current patch. Pinning by digest prevents silent tag drift and is the
# only way to guarantee the exact image we built against.
FROM eclipse-temurin@sha256:09349d79941fd53bb3d487b393ca118d8853c08c09193f416fe6a8718df9e732 AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml first (layer caching)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw clean package -DskipTests -B

# Rename the fat jar deterministically (Spring Boot leaves a
# '<artifact>-<version>.jar.original' alongside the repackaged fat jar,
# so the bare glob must exclude it)
RUN for f in target/rebase-rescue-*.jar; do \
      case "$f" in *.original) continue ;; *) mv "$f" target/app.jar; break ;; esac; \
    done \
 && ls target/app.jar

# NOTE: no Leyden AOT cache stage. Verified 2026-09-17: an AOT cache trained
# on this jar breaks startup — archived launcher classes report a null
# CodeSource, so JarLauncher cannot locate the archive (NPE in Archive.create).
# Plain launch boots in ~7s in-container, which needs no such tradeoff.
# Revisit only with an exploded-classes launch that keeps CodeSource intact.

# ============================================
# Runtime Stage
# ============================================
# Runtime Stage (Temurin 25 JRE, digest-pinned; see builder stage for
# why the old 25.0.4_1 tag was removed and 25.0.4_7 is current).
FROM eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682

LABEL org.opencontainers.image.title="RebaseRescue"
LABEL org.opencontainers.image.description="Self-hosted AI-powered code audit and PR analysis service"
LABEL org.opencontainers.image.vendor="kxng0109"
ARG APP_VERSION=2.0.0
LABEL org.opencontainers.image.version="${APP_VERSION}"
LABEL org.opencontainers.image.source="https://github.com/kxng0109/RebaseRescue"

WORKDIR /app

# Install required packages for health checks. Upgrade first: the digest-pinned
# base carries stale Alpine packages (expat, musl, openssl) with fixed CVEs
# available upstream. Upgrading at build time is intentional (security over
# bit-reproducibility); the base digest pin stays for provenance.
RUN apk upgrade --no-cache \
 && apk add --no-cache wget

# Create non-root user for security
RUN addgroup -S appgroup && \
    adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Copy the fat jar
COPY --from=builder --chown=appuser:appgroup /build/target/app.jar ./app.jar

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM configuration for containerized environments
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:+TieredCompilation \
               -XX:TieredStopAtLevel=1 \
               -Djava.security.egd=file:/dev/./urandom"
ENV JAVA_MAX_RAM_PERCENTAGE=75.0

# Run the application as a plain fat jar (see the AOT note above)
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE:-75.0} $JAVA_OPTS -jar /app/app.jar"]
