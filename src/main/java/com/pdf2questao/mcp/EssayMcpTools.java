package com.pdf2questao.mcp;

import com.pdf2questao.discursiva.Essay;
import com.pdf2questao.discursiva.EssayService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Discursiva: o Claude cria o estudo de caso, o usuario escreve no sistema e o Claude corrige. */
@Component
public class EssayMcpTools {

    private final EssayService essayService;
    private final String baseUrl;

    public EssayMcpTools(EssayService essayService, @Value("${server.port:8080}") int port) {
        this.essayService = essayService;
        this.baseUrl = "http://localhost:" + port;
    }

    public record EssayCreated(Long id, String titulo, String link) {
    }

    @McpTool(name = "criar_discursiva",
            description = "Cria uma prova discursiva para o usuário escrever no sistema (com tempo e contador de "
                    + "linhas). Pesquise antes as discursivas reais da banca (formato do estudo de caso, comandos, "
                    + "critérios de correção publicados) e imite. Não mostre a resposta esperada na conversa.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public EssayCreated createEssay(
            @McpToolParam(description = "Título, ex.: \"P3 – Estudo de caso: LOA e contingenciamento\".") String titulo,
            @McpToolParam(description = "Enunciado completo (caso, dados, comandos), como na prova real.") String enunciado,
            @McpToolParam(description = "Banca imitada.", required = false) String banca,
            @McpToolParam(description = "Critérios de correção com a pontuação de cada um, um por linha.") String criterios,
            @McpToolParam(description = "Tempo em minutos.", required = false) Integer tempoMinutos,
            @McpToolParam(description = "Máximo de linhas da folha de respostas.", required = false) Integer linhas,
            @McpToolParam(description = "Nota máxima (ex.: 120).", required = false) Double notaMaxima) {
        Essay essay = essayService.create(titulo, enunciado, banca, criterios, tempoMinutos, linhas, notaMaxima);
        return new EssayCreated(essay.getId(), essay.getTitle(), baseUrl + "/discursivas/" + essay.getId());
    }

    public record EssaySummary(Long id, String titulo, String situacao, Double nota, Double notaMaxima,
                               LocalDateTime entregueEm, String link) {
    }

    @McpTool(name = "listar_discursivas",
            description = "Discursivas do usuário e a situação de cada uma (nova, em andamento, entregue, corrigida).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<EssaySummary> listEssays() {
        return essayService.all().stream().map(e -> new EssaySummary(e.getId(), e.getTitle(),
                e.isCorrected() ? "corrigida" : e.isSubmitted() ? "entregue" : e.getStartedAt() != null
                        ? "em andamento" : "nova", e.getScore(), e.getMaxScore(), e.getSubmittedAt(),
                baseUrl + "/discursivas/" + e.getId())).toList();
    }

    public record EssayText(Long id, String titulo, String banca, String enunciado, String criterios,
                            Integer linhasMaximas, int linhasUsadas, Long minutosEscrevendo, Integer tempoMinutos,
                            boolean entregue, String texto, Double nota, String correcao) {
    }

    @McpTool(name = "ler_discursiva",
            description = "Enunciado, critérios e o texto do usuário de uma discursiva, para corrigir.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public EssayText readEssay(@McpToolParam(description = "Id da discursiva.") Long id) {
        Essay e = essayService.find(id);
        return new EssayText(e.getId(), e.getTitle(), e.getBanca(), e.getPrompt(), e.getCriteria(), e.getMaxLines(),
                EssayService.lines(e.getText(), 70), e.getActiveMillis() == null ? null : e.getActiveMillis() / 60000,
                e.getTimeLimitMinutes(), e.isSubmitted(), e.getText(), e.getScore(), e.getFeedback());
    }

    public record EssayCorrected(Long id, double nota, Double notaMaxima, String link) {
    }

    @McpTool(name = "corrigir_discursiva",
            description = "Grava a correção de uma discursiva entregue: nota e comentários em Markdown simples (nota "
                    + "por critério, o que faltou, erros de língua, como reescrever trechos). Corrija com o rigor da "
                    + "banca. Pode ser chamada de novo para refazer a correção.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public EssayCorrected correctEssay(
            @McpToolParam(description = "Id da discursiva.") Long id,
            @McpToolParam(description = "Nota final.") double nota,
            @McpToolParam(description = "Correção detalhada em Markdown (## títulos, listas com - , **negrito**).")
            String correcao) {
        Essay e = essayService.correct(id, nota, correcao);
        return new EssayCorrected(e.getId(), e.getScore(), e.getMaxScore(), baseUrl + "/discursivas/" + e.getId());
    }
}
