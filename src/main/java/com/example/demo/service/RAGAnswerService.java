package com.example.demo.service;

import com.example.demo.config.RagProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RAGAnswerService {

    private static final String SYSTEM_PROMPT = """
        Você é um assistente que RESPONDE APENAS com base no CONTEXTO fornecido.
        Se a resposta não estiver no contexto, diga: "Não encontrei no contexto."
        
        REGRAS:
        - Use SOMENTE o CONTEXTO disponível para responder
        - Se a resposta não estiver no contexto, sugira procurar mais dados
        """;

    private final SearchService searchService;
    private final ChatClient chatClient;
    private final RagProperties ragProperties;
    private final CitationService citationService;

    public RAGAnswerService(SearchService searchService, ChatClient.Builder chatClient, RagProperties ragProperties, CitationService citationService) {
        this.searchService = searchService;
        this.chatClient = chatClient.build();
        this.ragProperties = ragProperties;
        this.citationService = citationService;
    }

    public AnswerResponse answer(String question, Integer k, Double alpha, Integer perDoc) {
        var searchParams = buildSearchParams(k, alpha, perDoc);

        var hits = searchService.hybridSearch(question, searchParams.topK(),
            searchParams.alpha(), searchParams.perDoc());

        var fullContents = searchService.searchTopDocsFullContents(question, searchParams.perDoc());
        var context = String.join("\n\n", fullContents);

        String answer = generateAnswer(question, context);
        var citations = citationService.createCitations(hits);

        return new AnswerResponse(answer, citations);
    }

    private String generateAnswer(String question, String context) {
        return chatClient.prompt()
            .messages(
                new SystemMessage(SYSTEM_PROMPT),
                new SystemMessage("CONTEXTO:\n" + context),
                new UserMessage(question)
            )
            .call()
            .content();
    }

    private SearchParams buildSearchParams(Integer k, Double alpha, Integer perDoc) {
        return new SearchParams(
            k != null ? k : ragProperties.getSearch().getDefaultK(),
            alpha != null ? alpha : ragProperties.getSearch().getDefaultAlpha(),
            perDoc != null ? perDoc : ragProperties.getSearch().getDefaultPerDoc()
        );
    }

    private record SearchParams(int topK, double alpha, int perDoc) {}

    public record Citation(UUID documentId, int chunkIndex, String preview, double score) {}

    public record AnswerResponse(String answer, List<Citation> citations) {}

}
