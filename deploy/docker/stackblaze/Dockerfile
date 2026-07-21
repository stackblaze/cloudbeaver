# Stackblaze CloudBeaver image — official CE base + Access profile conf.
# Build from repo root:
#   docker build -f deploy/docker/stackblaze/Dockerfile -t ghcr.io/stackblaze/cloudbeaver:TAG .
ARG CLOUDBEAVER_BASE=dbeaver/cloudbeaver:25.2.0
FROM ${CLOUDBEAVER_BASE}

USER root
COPY deploy/k8s/cloudbeaver.conf /opt/cloudbeaver/conf/cloudbeaver.conf
RUN chown dbeaver:dbeaver /opt/cloudbeaver/conf/cloudbeaver.conf || true
USER dbeaver
