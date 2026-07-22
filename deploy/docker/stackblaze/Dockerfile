# Stackblaze CloudBeaver image — official CE base + Access profile conf.
# Build from repo root:
#   docker build -f deploy/docker/stackblaze/Dockerfile -t ghcr.io/stackblaze/cloudbeaver:TAG .
ARG CLOUDBEAVER_BASE=dbeaver/cloudbeaver:25.2.0
FROM ${CLOUDBEAVER_BASE}

USER root
COPY deploy/k8s/cloudbeaver.conf /opt/cloudbeaver/conf/cloudbeaver.conf
COPY deploy/docker/stackblaze/stackblaze.css /opt/cloudbeaver/web/stackblaze.css
# Inject communal chrome CSS into the SPA shell (profile / help / settings hide).
RUN sed -i 's|</head>|<link rel="stylesheet" href="./stackblaze.css" /></head>|' /opt/cloudbeaver/web/index.html \
  && chown dbeaver:dbeaver /opt/cloudbeaver/conf/cloudbeaver.conf /opt/cloudbeaver/web/stackblaze.css /opt/cloudbeaver/web/index.html || true
USER dbeaver
