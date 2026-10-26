#!/usr/bin/env bash
# Runs the test suite in a JDK 27 container, so no local Java install is needed.
# Testcontainers inside the container starts PostgreSQL through the host's Docker.
# Usage: scripts/test.sh [-Dtest=SomeTest]
set -euo pipefail
cd "$(dirname "$0")/.."

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$(pwd -W 2>/dev/null || pwd)":/src -w /src \
  -v ordermanager-m2:/root/.m2 \
  -v //var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3-eclipse-temurin-27@sha256:095c056353550ea93605f9cbd200a7cbbc489a1989eeec8b8600749a157a0096 \
  mvn -B -q test "$@"
