-- =========================================
-- V1__init.sql  (Flyway)
-- Cria schema/objetos p/ RAG com FTS + pgvector
-- =========================================

-- Criação mínima para RAG somente com pgvector

-- Extensões necessárias
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS vector;    -- pgvector (HNSW/IVFFLAT)

-- Tabela: documents
CREATE TABLE IF NOT EXISTS documents (
                                         id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT        NOT NULL,
    source_uri  TEXT,
    mime_type   TEXT,
    bytes       BIGINT,
    lang        TEXT        DEFAULT 'pt',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );

-- Tabela: document_chunks
-- Ajuste a dimensão do vetor conforme seu modelo (ex.: 768, 1024, 1536)
CREATE TABLE IF NOT EXISTS document_chunks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT  NOT NULL,
    content      TEXT NOT NULL,
    embedding    VECTOR(1024),
    token_count  INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    );

-- Unicidade por documento/ordem
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunks_doc_idx
    ON document_chunks (document_id, chunk_index);

-- Índice vetorial (HNSW) se disponível
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
    IF NOT EXISTS (
      SELECT 1
      FROM   pg_class c
      JOIN   pg_namespace n ON n.oid = c.relnamespace
      WHERE  c.relkind = 'i'
      AND    c.relname = 'idx_chunks_embedding_hnsw'
      AND    n.nspname = 'public'
    ) THEN
      EXECUTE 'CREATE INDEX idx_chunks_embedding_hnsw
               ON document_chunks
               USING hnsw (embedding vector_cosine_ops)';
END IF;
END IF;
END $$;
