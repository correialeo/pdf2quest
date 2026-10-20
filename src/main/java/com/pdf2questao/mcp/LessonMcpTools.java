package com.pdf2questao.mcp;

import com.pdf2questao.aula.Lesson;
import com.pdf2questao.aula.LessonService;
import com.pdf2questao.aula.LessonService.LessonResult;
import com.pdf2questao.aula.LessonService.NewQuestion;
import com.pdf2questao.aula.LessonService.TopicStat;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Tutor: aula sobre um topico do edital com quiz ligado a ela, respondido na tela do sistema; o resultado volta para
 * orientar reforcos e revisoes. O desempenho do quiz e separado do das provas e simulados.
 */
@Component
public class LessonMcpTools {

    private final LessonService lessonService;
    private final String baseUrl;

    public LessonMcpTools(LessonService lessonService, @Value("${server.port:8080}") int port) {
        this.lessonService = lessonService;
        this.baseUrl = "http://localhost:" + port;
    }

    @McpPrompt(name = "rotina_estudo_do_dia", title = "Rotina de estudo do dia",
            description = "Planeja o dia pelo cronograma, prepara a aula e o quiz no sistema e, depois que o usuário "
                    + "responde, agenda reforços e atualiza as revisões.")
    public String dailyRoutine(
            @McpArg(name = "foco", description = "Disciplina ou assunto para priorizar hoje (opcional).",
                    required = false) String foco) {
        return """
                Seja o tutor do usuário hoje, usando o servidor MCP pdf2questao. %s

                1. PLANEJAMENTO. Consulte tarefas_de_hoje, revisoes_de_hoje, desempenho_quiz (quizzes, por tópico), \\
                resumo_de_erros e desempenho_por_disciplina (questões de prova/simulado). Escolha o tópico principal \\
                (tarefa do cronograma de hoje) e, se couber, um reforço do que o usuário vem errando. Confira o \\
                tópico no conteúdo programático do edital (estrutura_do_edital / ler_edital) e diga ao usuário, em \\
                3 a 5 linhas, o plano do dia e por quê.

                2. AULA. Antes de escrever, veja o que o usuário já anotou (buscar_paginas / ler_pagina) e como a \\
                banca cobra o tópico (exemplos_da_banca). Escreva a aula com criar_aula, no nível dele (use o \\
                desempenho): explicação direta, exemplos, "como a FCC cobra" (formatos e pegadinhas observados nas \\
                questões reais), quadro de diferenças entre conceitos que se confundem e um resumo final. Para lei \\
                ou norma, use a redação vigente confirmada na fonte oficial e cite o dispositivo.

                3. QUIZ. Com adicionar_quiz, crie de 6 a 10 perguntas baseadas no que a aula ensinou (não em outra \\
                coisa), da mais simples à aplicada, com 4 ou 5 alternativas. Para CADA alternativa, a explicação de \\
                por que está certa ou errada (ela aparece quando o usuário clica). Informe o tópico de cada uma. \\
                Passe o link da aula ao usuário e peça para ele estudar e responder no sistema. Não mostre as \\
                respostas na conversa.

                4. DEPOIS DO QUIZ (quando o usuário avisar). Leia resultado_quiz. Explique os erros em poucas \\
                linhas. Se ele errou um tópico, agende um reforço com criar_tarefa (em 1 a 2 dias, descrição com \\
                o que confundiu) e confirme com ele. Conclua a tarefa do cronograma com concluir_tarefa (minutos \\
                informados por ele), registre o resumo com anexar_ao_resumo_do_dia e, se houver revisões do dia, \\
                faça-as com marcar_revisao. Termine dizendo o que vem amanhã.
                """.formatted(McpText.blank(foco) ? "" : "Foco pedido: " + foco + ".");
    }

    public record LessonCreated(Long aulaId, String titulo, String link) {
    }

    @McpTool(name = "criar_aula",
            description = "Cria uma aula no sistema (Markdown: ## títulos, listas, **negrito**, `código`, blocos ``` , "
                    + "citações > e listas numeradas). Vincule à disciplina e ao tópico do edital; depois crie o quiz "
                    + "com adicionar_quiz.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public LessonCreated createLesson(
            @McpToolParam(description = "Título da aula.") String titulo,
            @McpToolParam(description = "Disciplina (como no perfil do edital).") String disciplina,
            @McpToolParam(description = "Tópico do conteúdo programático do edital.") String topicoEdital,
            @McpToolParam(description = "Conteúdo da aula em Markdown.") String conteudo,
            @McpToolParam(description = "Id da tarefa do cronograma que a aula cobre.", required = false) Long tarefaId) {
        Lesson lesson = lessonService.create(titulo, disciplina, topicoEdital, conteudo, tarefaId);
        return new LessonCreated(lesson.getId(), lesson.getTitle(), baseUrl + "/aulas/" + lesson.getId());
    }

    public record QuizAlternative(
            @McpToolParam(description = "Texto da alternativa.") String texto,
            @McpToolParam(description = "Por que está certa ou errada (mostrado ao clicar).") String explicacao) {
    }

    public record QuizItem(
            @McpToolParam(description = "Enunciado.") String enunciado,
            @McpToolParam(description = "Tópico cobrado (para o desempenho por tópico).", required = false) String topico,
            @McpToolParam(description = "Alternativas na ordem A, B, C...") List<QuizAlternative> alternativas,
            @McpToolParam(description = "Letra correta.") String correta) {
    }

    public record QuizAdded(Long aulaId, int perguntasNoQuiz, String link) {
    }

    @McpTool(name = "adicionar_quiz",
            description = "Acrescenta perguntas ao quiz de uma aula, baseadas no que a aula ensinou. Cada alternativa "
                    + "precisa da explicação de por que está certa ou errada.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public QuizAdded addQuiz(
            @McpToolParam(description = "Id da aula.") Long aulaId,
            @McpToolParam(description = "Perguntas.") List<QuizItem> perguntas) {
        lessonService.addQuestions(aulaId, perguntas.stream().map(p -> new NewQuestion(p.enunciado(), p.topico(),
                p.alternativas() == null ? null : p.alternativas().stream().map(QuizAlternative::texto).toList(),
                p.alternativas() == null ? null : p.alternativas().stream().map(QuizAlternative::explicacao).toList(),
                p.correta())).toList());
        return new QuizAdded(aulaId, lessonService.questions(aulaId).size(), baseUrl + "/aulas/" + aulaId);
    }

    @McpTool(name = "resultado_quiz",
            description = "Resultado do quiz de uma aula (o que o usuário respondeu, acertos e a explicação da "
                    + "alternativa que ele marcou). Sem id, a aula mais recente.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public LessonResult quizResult(
            @McpToolParam(description = "Id da aula (vazio = a mais recente).", required = false) Long aulaId) {
        Long id = aulaId != null ? aulaId : lessonService.all().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nenhuma aula ainda.")).getId();
        return lessonService.result(id);
    }

    public record LessonSummary(Long aulaId, String titulo, String disciplina, String topicoEdital, LocalDate criadaEm,
                                boolean quizFeito, String link) {
    }

    @McpTool(name = "listar_aulas",
            description = "Aulas já criadas, as mais recentes primeiro, e se o quiz foi feito.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<LessonSummary> listLessons() {
        return lessonService.all().stream().limit(30).map(l -> new LessonSummary(l.getId(), l.getTitle(), l.getSubject(),
                l.getEditalTopic(), l.getCreatedAt().toLocalDate(), l.getFinishedAt() != null,
                baseUrl + "/aulas/" + l.getId())).toList();
    }

    @McpTool(name = "desempenho_quiz",
            description = "Acertos nos quizzes por disciplina e tópico (última resposta de cada pergunta), piores "
                    + "primeiro. É separado do desempenho em provas/simulados (desempenho_por_disciplina).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<TopicStat> quizPerformance() {
        return lessonService.stats();
    }
}
