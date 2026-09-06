# The API, as one container. The React app is deployed separately as static
# files — see docs/adr/0012-two-deployables.md for why they are not bundled
# into a single image.
#
# Two stages so the runtime image carries a JRE and a jar and nothing else: no
# Maven, no source, no ~/.m2. That is the difference between an image of a few
# hundred megabytes and one well over a gigabyte, and a smaller image is also a
# smaller attack surface.

# ---- Build ----
#
# Maven comes with the image rather than from ./mvnw. The wrapper is the right
# entry point for a person with a clone — it needs no Maven installed and it
# pins the distribution by checksum — but it is the wrong one here: the
# eclipse-temurin images ship no curl, wget or unzip, so the wrapper falls back
# to downloading with a small Java program and then cannot unpack the result.
# The failure it produces is a checksum mismatch, which reads alarmingly like a
# compromised download and is really a missing `curl`.
#
# This tag pins the same Maven 3.9.16 that .mvn/wrapper/maven-wrapper.properties
# pins, so the image and a developer's laptop build with one version. Keep them
# in step when either moves.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /build

# The POM is copied first, on its own, so Docker can cache the dependency
# download. Source changes far more often than dependencies do; without this
# split, editing one Java file would re-resolve the entire dependency tree on
# every build.
COPY pom.xml ./
RUN mvn --batch-mode dependency:go-offline

COPY src/ src/
# Tests are deliberately not run here. They need a Docker daemon for
# Testcontainers, which is not available inside a Docker build, and CI has
# already run them against the same commit — running them again here would
# mean maintaining a second, weaker way to test.
RUN mvn --batch-mode -DskipTests package

# ---- Run ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# A non-root user, because nothing in this container needs to be root and a
# process that cannot write outside its own directory is a much less useful
# foothold.
RUN useradd --system --create-home --shell /usr/sbin/nologin invoicely
USER invoicely

COPY --from=build /build/target/*.jar app.jar

# Documents the port; it does not publish it. The platform maps it, and
# SERVER_PORT can override it for a host that insists on its own.
EXPOSE 8080

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. With the shell
# form, the shell would be PID 1, the signal would stop at it, and the platform
# would eventually SIGKILL a JVM that never got the chance to shut down
# cleanly — which for this app means the scheduled overdue job could be killed
# mid-transaction.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
