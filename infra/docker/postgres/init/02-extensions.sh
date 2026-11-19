#!/bin/bash
# Extensions the migrations need but the (non-superuser) migration owner cannot create (AI platform, spec §4).
# vector: pgvector, for the AI knowledge index (ai_chunks.embedding).
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -c 'CREATE EXTENSION IF NOT EXISTS vector'
