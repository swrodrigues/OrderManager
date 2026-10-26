# PostgreSQL 18 on Chainguard's minimal image (Wolfi), pinned by digest.
# Chainguard rebuilds its packages as soon as fixes are published, so this image scans clean, unlike
# postgres:16-alpine, whose busybox has a CVE without an Alpine fix. To update, pull
# cgr.dev/chainguard/postgres:latest, rescan, and replace the digest here and in IntegrationTest.
FROM cgr.dev/chainguard/postgres@sha256:b32c0efe4c15bbc08fcd801f619a7d98d44f3f8eca3ec89efad4941a56e8741c

# Create the data directory owned by postgres, so a new volume is writable without starting as root.
USER root
RUN mkdir -p /var/lib/postgresql/data && chown postgres:postgres /var/lib/postgresql/data && chmod 700 /var/lib/postgresql/data

# The server never runs as root (the entrypoint only switches users when started as root).
USER postgres
