# Multi-stage Dockerfile for CFG Extractor
# Stage 1: Build the Java application with Gradle
FROM gradle:8.5-jdk17 AS builder

WORKDIR /build

# Copy Gradle files first for better layer caching
COPY build.gradle settings.gradle ./

# Download dependencies (cached if build files haven't changed)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build the application
RUN gradle clean build -x test --no-daemon

# Verify JAR was created
RUN ls -lh build/libs/

# Stage 2: Runtime image with Java and Python
FROM eclipse-temurin:17-jre-jammy

# Install Python and required tools
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-dev \
    graphviz \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /build/build/libs/cfg-extractor-1.0-SNAPSHOT.jar /app/cfg-extractor.jar

# Copy visualization scripts
COPY visualize/ /app/visualize/

# Install Python dependencies
RUN pip3 install --no-cache-dir -r /app/visualize/requirements.txt

# Copy entrypoint script
COPY docker/docker-entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Create directories for input/output
RUN mkdir -p /input /output

# Set environment variables
ENV JAVA_OPTS="-Xmx2g"
ENV PYTHONUNBUFFERED=1

# Set entrypoint
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# Default command shows help
CMD ["--help"]
