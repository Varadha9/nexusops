FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 FASTEMBED_CACHE_PATH=/opt/models HF_HUB_OFFLINE=0
WORKDIR /app
RUN apt-get update && apt-get upgrade -y --no-install-recommends && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml ./
COPY app app
# Install, bake the embedding model into the image (so the runtime needs no network and the
# non-root user can read it), then drop the packaging toolchain: the runtime needs neither pip
# nor setuptools (and their vendored libraries carry their own CVEs).
RUN pip install --no-cache-dir . \
    && python -c "from fastembed import TextEmbedding; TextEmbedding('BAAI/bge-small-en-v1.5')" \
    && chmod -R a+rX /opt/models \
    && pip uninstall -y pip setuptools wheel \
    && useradd --system --uid 10001 --no-create-home app
ENV HF_HUB_OFFLINE=1
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
