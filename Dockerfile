# syntax=docker/dockerfile:1

# ---- build: Maven runs inside the container, nothing needed on the host but Docker ----
FROM maven:3-eclipse-temurin-27@sha256:095c056353550ea93605f9cbd200a7cbbc489a1989eeec8b8600749a157a0096 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/order-manager-*.jar /app.jar

# ---- runtime parts: a trimmed Java 27 runtime and the files the final image needs ----
FROM eclipse-temurin:27-jdk-alpine@sha256:63c4f0092cb994c7a8c0446aace33a2de3763128fcc68c211d7ad3577c75b59f AS runtime
COPY --from=build /app.jar /tmp/app.jar
# Unpack the Spring Boot jar (faster start), then build a JRE with only the modules the app uses.
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app \
 && MODULES="$(jdeps --ignore-missing-deps -q --recursive --multi-release 27 --print-module-deps \
                 --class-path '/app/lib/*' /app/app.jar)" \
 && jlink --add-modules "${MODULES},jdk.crypto.ec,jdk.unsupported,java.instrument,jdk.management" \
          --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre
# A minimal root filesystem: the musl C library (the only native library this JRE needs), a non-root
# user, and a writable /tmp for Tomcat.
RUN mkdir -p /rootfs/lib /rootfs/etc /rootfs/tmp /rootfs/home/app \
 && cp /lib/ld-musl-x86_64.so.1 /rootfs/lib/ \
 && ln -s ld-musl-x86_64.so.1 /rootfs/lib/libc.musl-x86_64.so.1 \
 && echo 'app:x:10001:10001:app:/home/app:/sbin/nologin' > /rootfs/etc/passwd \
 && echo 'app:x:10001:' > /rootfs/etc/group \
 && chown 10001:10001 /rootfs/home/app && chmod 1777 /rootfs/tmp

# ---- final image: no OS packages, no shell, no package manager ----
FROM scratch
COPY --from=runtime /rootfs/ /
COPY --from=runtime /jre /opt/java
COPY --from=runtime /app /app
ENV JAVA_HOME=/opt/java
USER 10001:10001
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
