package com.example.demo.service;

import com.example.demo.client.LmStudioEmbeddingClient;
import com.example.demo.config.EmbeddingProperties;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EmbeddingJobService {

    private static final String UPDATE_SQL = "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final LmStudioEmbeddingClient lm;
    private final EmbeddingProperties props;

    /**
     * Realiza o processo de geração e persistência de embeddings para todos os chunks
     * de um documento específico que ainda não possuem embedding. Divide em partições,
     * filtra conteúdo válido, solicita embeddings em lote e faz batch update no banco.
     * @param docId ID do documento cujos chunks serão processados.
     */
    public void embedDocument(final UUID docId) {
        List<Row> rows = fetchPendingChunks(docId);
        if (CollectionUtils.isNotEmpty(rows)) {
            List<Object[]> pendingBatch = new ArrayList<>(props.getBatchSize());

            this.partitions(rows, props.getBatchSize())
                .map(this::filterValidRows)
                .filter(list -> !list.isEmpty())
                .map(this::tryEmbedPartition)
                .flatMap(Optional::stream)
                .forEach(batchEntries -> {
                    pendingBatch.addAll(batchEntries);
                    flushIfFull(pendingBatch);
                });
            flushRemaining(pendingBatch);
        }
    }

    /**
     * Divide uma lista em partições de tamanho máximo definido.
     * @param list lista completa de elementos.
     * @param size tamanho máximo de cada partição (deve ser > 0).
     * @return stream contendo sublistas (partições) na ordem original.
     */
    private Stream<List<Row>> partitions(List<Row> list, int size) {
        return ListUtils.partition(list, size).stream();
    }

    /**
     * Recupera do banco todos os chunks de um documento que ainda não possuem embedding,
     * ordenados pela posição (chunk_index).
     * @param docId ID do documento.
     * @return lista de linhas (chunks) pendentes.
     */
    private List<Row> fetchPendingChunks(UUID docId) {
        return jdbc.query("""
            SELECT id, content
            FROM document_chunks
            WHERE document_id = ? AND embedding IS NULL
            ORDER BY chunk_index
            """,
            (rs, i) -> new Row((UUID) rs.getObject("id"), rs.getString("content")),
            docId
        );
    }

    /**
     * Remove da partição os registros cujo conteúdo é nulo, vazio ou só espaços.
     * @param group sublista de rows.
     * @return lista somente com conteúdo textual válido.
     */
    private List<Row> filterValidRows(List<Row> group) {
        return group.stream()
            .filter(r -> StringUtils.isNotBlank(r.content))
            .toList();
    }

    /**
     * Solicita embeddings ao cliente externo para a lista de linhas válida.
     * Se a quantidade de embeddings retornada não corresponder, retorna vazio.
     * Caso tenha sucesso, converte cada embedding em parâmetro pronto para batch update.
     *
     * @param validRows linhas com conteúdo válido.
     * @return Optional contendo lista de arrays de parâmetros (embedding literal + id) ou vazio em falha.
     */
    private Optional<List<Object[]>> tryEmbedPartition(List<Row> validRows) {
        List<String> inputs = validRows.stream().map(Row::content).toList();
        List<float[]> embeddings = lm.embedBatch(inputs, props.getModel(), props.getRequestTimeout());
        if (embeddings.size() != validRows.size()) return Optional.empty();
        List<Object[]> entries = IntStream.range(0, validRows.size())
            .mapToObj(i -> new Object[]{toPgVectorLiteral(embeddings.get(i)), validRows.get(i).id()})
            .toList();
        return Optional.of(entries);
    }

    /**
     * Executa batch update e limpa a lista acumulada se o tamanho atingir o limite configurado.
     * @param pending lista acumulada de parâmetros para update.
     */
    private void flushIfFull(List<Object[]> pending) {
        if (pending.size() >= props.getBatchSize()) {
            jdbc.batchUpdate(UPDATE_SQL, pending);
            pending.clear();
        }
    }

    /**
     * Envia ao banco qualquer resto de parâmetros ainda não persistidos.
     * @param pending lista acumulada remanescente.
     */
    private void flushRemaining(List<Object[]> pending) {
        if (!pending.isEmpty()) {
            jdbc.batchUpdate(UPDATE_SQL, pending);
            pending.clear();
        }
    }

    /**
     * Converte um vetor de floats em literal compatível com o tipo vector do PostgreSQL
     * (ex: [0.12340000,0.00001230,...]) usando Locale.US para padronizar decimal com ponto.
     * @param v vetor de floats.
     * @return string formatada no padrão aceito pelo extension pgvector.
     */
    private static String toPgVectorLiteral(float[] v) {
        return java.util.stream.IntStream.range(0, v.length)
            .mapToObj(i -> String.format(Locale.US, "%.8f", v[i]))
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    record Row(UUID id, String content) {}
}