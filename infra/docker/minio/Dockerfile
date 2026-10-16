# MinIO no longer publishes official container images (see docs/phase.md → Open issues), so the local stack
# runs Chainguard's free MinIO build. That image contains only the minio binary (no shell, curl or wget);
# this adds a statically linked BusyBox so the compose healthcheck can probe /minio/health/live.
ARG MINIO_IMAGE=cgr.dev/chainguard/minio:latest
ARG BUSYBOX_IMAGE=busybox:stable-musl

FROM ${BUSYBOX_IMAGE} AS busybox

FROM ${MINIO_IMAGE}
COPY --from=busybox /bin/busybox /usr/local/bin/busybox
