package com.pdf2questao.mcp;

import com.pdf2questao.concurso.PriorityService;
import com.pdf2questao.concurso.PriorityService.Priorities;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** O que estudar primeiro, pelos pontos que ainda escapam na prova. */
@Component
public class PriorityMcpTools {

    private final PriorityService priorityService;
    private final McpConcurso mcpConcurso;

    public PriorityMcpTools(PriorityService priorityService, McpConcurso mcpConcurso) {
        this.priorityService = priorityService;
        this.mcpConcurso = mcpConcurso;
    }

    @McpTool(name = "prioridades",
            description = "Disciplinas do edital na ordem do que dá mais ponto estudar agora: pontos em jogo (peso x "
                    + "questões) x o que falta de acerto (provas/simulados e quizzes; quiz abaixo do nível prova vale "
                    + "metade; tópico do edital sem aula nem quiz conta como 50%). Traz os tópicos mais fracos e os "
                    + "não estudados de cada uma. É sugestão para o plano do dia: não altera o cronograma; mostre ao "
                    + "usuário e deixe ele decidir.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public Priorities priorities(
            @McpToolParam(description = McpConcurso.PARAM, required = false) String concurso) {
        Long id = mcpConcurso.id(concurso);
        if (id == null) {
            throw new IllegalArgumentException("Nenhum concurso ativo: informe o concurso (listar_concursos).");
        }
        return priorityService.priorities(id);
    }
}
