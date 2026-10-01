FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 MOVIA_DATA_DIR=/data \
    MOVIA_CLOUD_MODE=1 MOVIA_P2P_ENABLED=0 MOVIA_ENRICH_WORKERS=2 PORT=8080
WORKDIR /opt/movia
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home movia \
    && mkdir /data && chown movia:movia /data
COPY --chown=movia:movia runtime/ ./runtime/
USER movia
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=3)"
CMD ["python", "runtime/cloud_main.py"]
