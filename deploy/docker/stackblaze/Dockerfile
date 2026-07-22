# Stackblaze CloudBeaver — from-source build with native Redis driver.
# Build context: repository root (cloudbeaver/).
#
#   docker build -f deploy/docker/stackblaze/Dockerfile -t ghcr.io/stackblaze/cloudbeaver:TAG .
#
# Layout inside the build stage mirrors upstream's sibling-checkout expectation:
#   /src/dbeaver, /src/dbeaver-common, /src/cloudbeaver

ARG JAVA_IMAGE=eclipse-temurin:21-jdk-noble
ARG NODE_VERSION=24
ARG MAVEN_VERSION=3.9.16
ARG DBEAVER_REF=devel
ARG DBEAVER_COMMON_REF=devel
# Must be global (before first FROM) so stage-2 FROM can resolve it.
ARG BASE_JAVA_TAG=stable

# ---------- Stage 1: build server (Maven/Tycho) + webapp (Yarn) ----------
FROM ${JAVA_IMAGE} AS build

ARG NODE_VERSION
ARG MAVEN_VERSION
ARG DBEAVER_REF
ARG DBEAVER_COMMON_REF

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_OPTS="-Xmx2g" \
    MAVEN_COMMON_OPTS="-T 1C -DskipTests" \
    PATH="/opt/apache-maven-${MAVEN_VERSION}/bin:${PATH}"

# Distro Maven breaks Tycho 5.x Guice wiring; use the same Apache Maven as CI.
RUN apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends \
        git curl ca-certificates unzip zip \
    && curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
    && curl -fsSL "https://deb.nodesource.com/setup_${NODE_VERSION}.x" | bash - \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends nodejs \
    && corepack enable \
    && mvn -version \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Sibling repos required by server/pom.xml (relativePath ../../dbeaver)
RUN git clone --depth 1 --branch "${DBEAVER_COMMON_REF}" \
      https://github.com/dbeaver/dbeaver-common.git dbeaver-common \
 && git clone --depth 1 --branch "${DBEAVER_REF}" \
      https://github.com/dbeaver/dbeaver.git dbeaver

# CloudBeaver sources (this repo)
COPY . /src/cloudbeaver

WORKDIR /src/cloudbeaver/deploy

# Backend: Tycho product + driver jars (including redis/Jedis)
RUN chmod +x ./build-backend.sh \
 && ./build-backend.sh

# Frontend SPA
RUN chmod +x ./build-frontend.sh \
 && ./build-frontend.sh

# ---------- Stage 2: runtime image ----------
ARG BASE_JAVA_TAG
FROM dbeaver/base-java:${BASE_JAVA_TAG}

ENV DBEAVER_GID=8978 \
    DBEAVER_UID=8978

RUN groupadd -g $DBEAVER_GID dbeaver && \
    useradd -g $DBEAVER_GID -m -u $DBEAVER_UID -s /bin/bash dbeaver

COPY --from=build /src/cloudbeaver/deploy/cloudbeaver /opt/cloudbeaver
COPY deploy/scripts/launch-product.sh /opt/cloudbeaver/launch-product.sh

# Stackblaze Access profile + communal chrome CSS
COPY deploy/k8s/cloudbeaver.conf /opt/cloudbeaver/conf/cloudbeaver.conf
COPY deploy/docker/stackblaze/stackblaze.css /opt/cloudbeaver/web/stackblaze.css
RUN sed -i 's|</head>|<link rel="stylesheet" href="./stackblaze.css" /></head>|' /opt/cloudbeaver/web/index.html \
  && find /opt/cloudbeaver -type d -exec chmod 775 {} \; \
  && chmod +x /opt/cloudbeaver/run-cloudbeaver-server.sh /opt/cloudbeaver/launch-product.sh \
  && chown -R dbeaver:dbeaver /opt/cloudbeaver

EXPOSE 8978
WORKDIR /opt/cloudbeaver/
USER dbeaver
ENTRYPOINT ["./launch-product.sh"]
