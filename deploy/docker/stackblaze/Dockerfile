# Stackblaze CloudBeaver — from-source build with native Redis driver.
# Build context: repository root (cloudbeaver/).
#
#   docker build -f deploy/docker/stackblaze/Dockerfile -t ghcr.io/stackblaze/cloudbeaver:TAG .
#
# Layout mirrors upstream sibling-checkout expectation:
#   /src/dbeaver, /src/dbeaver-common, /src/cloudbeaver

ARG JAVA_IMAGE=eclipse-temurin:21-jdk-noble
ARG NODE_IMAGE=node:24.13.1-bookworm
ARG MAVEN_VERSION=3.9.16
# Pinned to the last upstream SHAs this fork compiles against (2026-08-18).
# Upstream `devel` drifted past our code (BaseServletPlatform File->Path) and
# an unpinned build breaks with NO change in this repo. Fetch-by-SHA below
# also accepts branch names, so overriding stays possible. Bump these together
# with an upstream catch-up merge.
ARG DBEAVER_REF=71879f4671616d0aa685a885a923944e331c5c85
ARG DBEAVER_COMMON_REF=2df03e58480c6325732f476e8355e375cec5405e
# Must be global (before first FROM) so later FROM lines can resolve it.
ARG BASE_JAVA_TAG=stable

# ---------- Stage 1: backend (Maven/Tycho) ----------
FROM ${JAVA_IMAGE} AS backend

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
    && mvn -version \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# `git clone --branch` refuses SHAs; init+fetch-by-SHA handles SHAs AND
# branch names (GitHub serves reachable SHAs to shallow fetches).
RUN git init -q dbeaver-common \
 && git -C dbeaver-common remote add origin https://github.com/dbeaver/dbeaver-common.git \
 && git -C dbeaver-common fetch -q --depth 1 origin "${DBEAVER_COMMON_REF}" \
 && git -C dbeaver-common checkout -q FETCH_HEAD \
 && git init -q dbeaver \
 && git -C dbeaver remote add origin https://github.com/dbeaver/dbeaver.git \
 && git -C dbeaver fetch -q --depth 1 origin "${DBEAVER_REF}" \
 && git -C dbeaver checkout -q FETCH_HEAD

COPY . /src/cloudbeaver

WORKDIR /src/cloudbeaver/deploy
RUN chmod +x ./build-backend.sh \
 && ./build-backend.sh

# ---------- Stage 2: frontend (match CI Node exactly) ----------
FROM ${NODE_IMAGE} AS frontend

# Tailwind 4.0.7 require.cache busting breaks under Node's native TS stripping.
ENV NODE_OPTIONS=--no-experimental-strip-types

WORKDIR /src/cloudbeaver
COPY . /src/cloudbeaver

RUN corepack enable

WORKDIR /src/cloudbeaver/deploy
RUN chmod +x ./build-frontend.sh \
 && ./build-frontend.sh

# ---------- Stage 3: runtime image ----------
ARG BASE_JAVA_TAG
FROM dbeaver/base-java:${BASE_JAVA_TAG}

ENV DBEAVER_GID=8978 \
    DBEAVER_UID=8978

RUN groupadd -g $DBEAVER_GID dbeaver && \
    useradd -g $DBEAVER_GID -m -u $DBEAVER_UID -s /bin/bash dbeaver

COPY --from=backend /src/cloudbeaver/deploy/cloudbeaver /opt/cloudbeaver
COPY --from=frontend /src/cloudbeaver/deploy/cloudbeaver/web /opt/cloudbeaver/web
COPY deploy/scripts/launch-product.sh /opt/cloudbeaver/launch-product.sh

# Stackblaze Access profile + communal chrome CSS
COPY deploy/k8s/cloudbeaver.conf /opt/cloudbeaver/conf/cloudbeaver.conf
COPY deploy/docker/stackblaze/stackblaze.css /opt/cloudbeaver/web/stackblaze.css
# Inject communal chrome CSS into the SPA shell (profile / help / settings hide).
RUN sed -i 's|</head>|<link rel="stylesheet" href="./stackblaze.css" /></head>|' /opt/cloudbeaver/web/index.html \
  && find /opt/cloudbeaver -type d -exec chmod 775 {} \; \
  && chmod +x /opt/cloudbeaver/run-cloudbeaver-server.sh /opt/cloudbeaver/launch-product.sh \
  && chown -R dbeaver:dbeaver /opt/cloudbeaver

EXPOSE 8978
WORKDIR /opt/cloudbeaver/
USER dbeaver
ENTRYPOINT ["./launch-product.sh"]
