FROM python:3.14-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MOVIA_CLOUD_MODE=1 \
    MOVIA_P2P_ENABLED=0 \
    MOVIA_BACKGROUND_TORRENT_LOOKUP=0 \
    MOVIA_HOST=0.0.0.0 \
    MOVIA_PORT=8080

WORKDIR /app/backend

COPY backend/requirements.txt /tmp/requirements.txt
RUN python -m pip install --no-cache-dir -r /tmp/requirements.txt

COPY backend/ /app/backend/

# Runtime databases, caches, logs and secrets are mounted/provisioned at deploy
# time. They are deliberately not baked into the image.
RUN rm -rf \
      /app/backend/catalog.db \
      /app/backend/catalog.db-* \
      /app/backend/stream_cache \
      /app/backend/torrent_cache \
      /app/backend/logs \
      /app/backend/backups \
      /app/backend/.env \
    && mkdir -p /app/backend/stream_cache /app/backend/logs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; r=urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=3); raise SystemExit(0 if r.status == 200 else 1)"

CMD ["sh", "-c", "python cloud_runtime_preflight.py && exec python -u streamer.py"]
