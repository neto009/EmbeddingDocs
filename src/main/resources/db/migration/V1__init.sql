-- =========================================
-- V1__init.sql  (Flyway)
-- Cria schema/objetos p/ RAG com FTS + pgvector
-- =========================================

-- Extensões necessárias (podem exigir permissão de CREATE EXTENSION)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid(), digest()
CREATE EXTENSION IF NOT EXISTS vector;    -- pgvector (HNSW/IVFFLAT)
CREATE EXTENSION IF NOT EXISTS unaccent;  -- Remover acentuacao

-- =========================================
-- TABELA: documents
-- =========================================
CREATE TABLE IF NOT EXISTS documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- use UUIDv7 no app, se quiser
    title       TEXT        NOT NULL,
    source_uri  TEXT,
    mime_type   TEXT,
    bytes       BIGINT,
    lang        TEXT        DEFAULT 'pt',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );

COMMENT ON TABLE  documents       IS 'Arquivos/Documentos ingeridos';
COMMENT ON COLUMN documents.lang  IS 'Idioma principal (ex: pt, en)';

-- =========================================
-- TABELA: document_chunks
-- =========================================
-- Ajuste a dimensão do vetor conforme seu modelo de embedding
-- (ex.: 768, 1536, 3072).
CREATE TABLE IF NOT EXISTS document_chunks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index  INT  NOT NULL,
    content      TEXT NOT NULL,

    -- Agora simples coluna; valor será mantido pelo trigger.
    content_hash BYTEA,

    -- tsv não pode ser GENERATED (to_tsvector é STABLE); manter via trigger.
    tsv          TSVECTOR,

    embedding    VECTOR(1024),
    char_start   INT,
    char_end     INT,
    token_count  INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    );

COMMENT ON TABLE document_chunks IS 'Chunks de documentos com FTS e embedding (pgvector)';
COMMENT ON COLUMN document_chunks.embedding IS 'Dimensão deve bater com o modelo de embedding usado';

-- Unicidade: evita (document_id, chunk_index) duplicado
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunks_doc_idx
    ON document_chunks (document_id, chunk_index);

-- Evita inserir o MESMO texto 2x no MESMO documento
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunks_doc_hash
    ON document_chunks (document_id, content_hash);

-- Índice FTS (GIN)
CREATE INDEX IF NOT EXISTS idx_chunks_tsv_gin
    ON document_chunks USING GIN (tsv);

-- Índice por documento (consulta/paginação ordenada)
CREATE INDEX IF NOT EXISTS idx_chunks_docid
    ON document_chunks (document_id, chunk_index);

-- =========================================
-- ÍNDICE VETORIAL (ANN) - HNSW (pgvector >= 0.6)
-- Cria somente se não existir e se a extensão vector estiver disponível
-- =========================================
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
      -- Opcional: WITH (m=16, ef_construction=64)
END IF;
END IF;
END $$;

-- =========================================
-- TRIGGER ÚNICA para manter tsv + content_hash
-- =========================================
CREATE OR REPLACE FUNCTION f_document_chunks_maintain()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- FTS (config 'portuguese' Removendo acentuação)
  NEW.tsv := to_tsvector('portuguese', unaccent(COALESCE(NEW.content, '')));

  -- Hash do conteúdo (deduplicação por documento)
  NEW.content_hash := digest(convert_to(COALESCE(NEW.content, ''), 'UTF8'), 'sha256');

RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_document_chunks_maintain ON document_chunks;

CREATE TRIGGER trg_document_chunks_maintain
    BEFORE INSERT OR UPDATE OF content ON document_chunks
    FOR EACH ROW
    EXECUTE FUNCTION f_document_chunks_maintain();

-- =========================================
-- Dicas pós-carga (rode via app/script, não aqui):
--   ANALYZE document_chunks;
--   -- Para HNSW:
--   -- SET hnsw.ef_search = 64;          -- sessão (ajuste recall/latência)
--   -- Para IVFFLAT (se preferir):
--   -- SET ivfflat.probes = 8;
-- =========================================
