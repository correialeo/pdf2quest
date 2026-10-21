package com.pdf2questao.mcp;

import com.pdf2questao.aula.LessonService;
import com.pdf2questao.dashboard.DashboardService;
import com.pdf2questao.dashboard.SubjectStat;
import com.pdf2questao.importer.structured.ImportOptions;
import com.pdf2questao.importer.structured.ImportOutcome;
import com.pdf2questao.importer.structured.ImportPreview;
import com.pdf2questao.importer.structured.PreviewItem;
import com.pdf2questao.importer.structured.QuestionDraft;
import com.pdf2questao.importer.structured.StructuredImportService;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator.ScoreResult;
import com.pdf2questao.session.AttemptRepository;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import com.pdf2questao.study.DaySummaryService;
import com.pdf2questao.study.SpacedRepetitionService;
import com.pdf2questao.study.StudyPage;
import com.pdf2questao.study.StudyPageService;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ferramentas MCP para o Claude Code estudar junto com o sistema: ler o desempenho e os erros, registrar o treino
 * feito no chat e cuidar da rotina do dia (tarefas, revisoes, resumo). Nada aqui apaga dados.
 */
@Component
public class StudyMcpTools {

    /** Teto de questoes devolvidas por chamada: o que a ferramenta devolve entra na conversa. */
    static final int MAX_ERRORS = 20;
    private static final int MAX_TEXT = 4000;

    private final DashboardService dashboardService;
    private final AttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final PassageRepository passageRepository;
    private final StructuredImportService importService;
    private final StudySessionService sessionService;
    private final StudyTaskService taskService;
    private final SpacedRepetitionService spacedRepetition;
    private final DaySummaryService daySummaryService;
    private final StudyPageService pageService;
    private final LessonService lessonService;
    private final String baseUrl;

    public StudyMcpTools(DashboardService dashboardService, AttemptRepository attemptRepository,
                         QuestionRepository questionRepository, PassageRepository passageRepository,
                         StructuredImportService importService, StudySessionService sessionService,
                         StudyTaskService taskService, SpacedRepetitionService spacedRepetition,
                         DaySummaryService daySummaryService, StudyPageService pageService,
                         LessonService lessonService, @Value("${server.port:8080}") int port) {
        this.dashboardService = dashboardService;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
        this.passageRepository = passageRepository;
        this.importService = importService;
        this.sessionService = sessionService;
        this.taskService = taskService;
        this.spacedRepetition = spacedRepetition;
        this.daySummaryService = daySummaryService;
        this.pageService = pageService;
        this.lessonService = lessonService;
        this.baseUrl = "http://localhost:" + port;
    }

    // ---- desempenho ----

    public record SubjectPerformance(String disciplina, long acertos, long respondidas, double percentual) {
    }

    @McpTool(name = "desempenho_por_disciplina",
            description = "Acertos por disciplina em todas as questões já respondidas no sistema (simulados, provas e "
                    + "treinos importados), piores primeiro. Use para decidir o que treinar.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<SubjectPerformance> subjectPerformance() {
        return attemptRepository.statsBySubject().stream()
                .map(r -> new SubjectStat((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                .sorted(Comparator.comparingDouble(SubjectStat::percent))
                .map(s -> new SubjectPerformance(s.label(), s.correct(), s.total(), round(s.percent())))
                .toList();
    }

    public record RuleProjection(String disciplina, double peso, int questoesNaSessao, int acertos,
                                 Double pontosProjetados, double maximoNoEdital) {
    }

    public record GroupProjection(String grupo, Double pontosProjetados, double maximo, Double minimo,
                                  Boolean bateMinimo, List<RuleProjection> disciplinas) {
    }

    public record LastSimulado(String nome, LocalDate data, String perfil, Double notaProjetada, double notaMaxima,
                               Double minimoTotal, Boolean passaria, Double coberturaDaProva,
                               List<GroupProjection> grupos, String link) {
    }

    @McpTool(name = "ultimo_simulado",
            description = "Último simulado finalizado com perfil de pontuação do edital: nota projetada para a prova "
                    + "inteira, mínimos por grupo e o que cada disciplina rende. Disciplinas sem questões na sessão "
                    + "vêm com pontosProjetados nulo. Retorna nulo se ainda não houve simulado com perfil.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public LastSimulado lastSimulado() {
        var today = dashboardService.today();
        StudySession s = today.lastScored();
        ScoreResult score = today.lastScore();
        if (s == null || score == null) {
            return null;
        }
        List<GroupProjection> groups = score.groups().stream().map(g -> new GroupProjection(g.name(),
                round(g.projectedPoints()), g.maxPoints(), g.minScore(), g.projectedMinMet(),
                g.rules().stream().map(r -> new RuleProjection(r.subject(), r.weight(), r.questions(), r.correct(),
                        round(r.projected()), r.maxPoints())).toList())).toList();
        return new LastSimulado(s.getBlockName(), s.getFinishedAt().toLocalDate(), score.profileName(),
                round(score.projectedTotal()), score.maxTotal(), score.minTotal(), score.projectedPassed(),
                round(score.coverage()), groups, baseUrl + "/resultado/" + s.getId());
    }

    // ---- erros ----

    public record ErrorQuestion(Long id, String disciplina, String assunto, String prova, Integer numero,
                                String textoBase, String enunciado, Map<String, String> alternativas,
                                String respostaCorreta, String suaResposta, String explicacao, String link) {
    }

    @McpTool(name = "listar_erros",
            description = "Questões do caderno de erros (a última resposta foi errada), as mais recentes primeiro, com "
                    + "enunciado, alternativas, resposta certa e a resposta do usuário. Use para explicar os erros e "
                    + "criar questões parecidas.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<ErrorQuestion> listErrors(
            @McpToolParam(description = "Filtra pela disciplina (parte do nome, sem diferenciar acentos). "
                    + "Vazio = todas.", required = false) String disciplina,
            @McpToolParam(description = "Quantas questões, de 1 a 20 (padrão 10).", required = false) Integer limite) {
        int limit = Math.max(1, Math.min(limite == null ? 10 : limite, MAX_ERRORS));
        String wanted = disciplina == null || disciplina.isBlank() ? null : normalize(disciplina);
        Map<Long, String> mine = new HashMap<>();
        for (Object[] row : attemptRepository.findLatestWrongAnswers()) {
            mine.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        Map<Long, String> passages = new HashMap<>();
        List<ErrorQuestion> out = new ArrayList<>();
        for (Question q : questionRepository.findErrorNotebook()) {
            if (wanted != null && (q.getSubject() == null || !normalize(q.getSubject()).contains(wanted))) {
                continue;
            }
            String passage = q.getPassageId() == null ? null : passages.computeIfAbsent(q.getPassageId(),
                    id -> passageRepository.findById(id).map(p -> clip(plain(p.getContent()))).orElse(null));
            Map<String, String> alternatives = new LinkedHashMap<>();
            q.alternatives().forEach((k, v) -> alternatives.put(k, clip(plain(v))));
            out.add(new ErrorQuestion(q.getId(), q.getSubject(), q.getTopic(), q.getExam(), q.getQuestionNumber(),
                    passage, clip(plain(q.getStatement())), alternatives, q.getCorrectAnswer(), mine.get(q.getId()),
                    q.getExplanation(), q.getImportJobId() == null ? null
                            : baseUrl + "/import/" + q.getImportJobId() + "/questoes"));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    // ---- treino ----

    public record TrainingQuestion(
            @McpToolParam(description = "Enunciado completo.") String enunciado,
            @McpToolParam(description = "Textos das alternativas na ordem A, B, C... (sem a letra). Omitir em "
                    + "certo/errado.", required = false) List<String> alternativas,
            @McpToolParam(description = "Resposta correta: letra (\"B\") ou \"Certo\"/\"Errado\".") String resposta,
            @McpToolParam(description = "O que o usuário respondeu (mesmo formato). Omitir se não respondeu.",
                    required = false) String suaResposta,
            @McpToolParam(description = "true se o usuário disse que chutou.", required = false) Boolean chute,
            @McpToolParam(description = "Disciplina; use sempre o mesmo nome para a mesma matéria (veja "
                    + "desempenho_por_disciplina).") String disciplina,
            @McpToolParam(description = "Assunto dentro da disciplina.", required = false) String assunto,
            @McpToolParam(description = "Por que a correta está certa e onde o usuário errou.", required = false)
            String explicacao,
            @McpToolParam(description = "Texto de apoio, se houver.", required = false) String textoBase) {

        QuestionDraft toDraft() {
            return new QuestionDraft(enunciado, alternativas, resposta, suaResposta, chute, disciplina, assunto,
                    explicacao, textoBase);
        }
    }

    public record TrainingResult(Integer questoesNovas, Integer jaExistiam, Integer acertos, Integer erros,
                                 List<String> avisos, String link, String erro) {
    }

    @McpTool(name = "registrar_treino",
            description = "Grava as questões resolvidas nesta conversa, com as respostas do usuário, como uma sessão de "
                    + "treino já corrigida: erros e chutes entram no caderno de erros e no desempenho por "
                    + "disciplina. Chame no fim do treino com todas as questões. Questões com erro de formato são "
                    + "ignoradas e voltam em 'avisos'.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public TrainingResult recordTraining(
            @McpToolParam(description = "Título do treino, ex.: \"Treino Claude – AFO – 20/10\".") String titulo,
            @McpToolParam(description = "Banca imitada, ex.: \"FCC\".", required = false) String banca,
            @McpToolParam(description = "Questões resolvidas, na ordem.") List<TrainingQuestion> questoes) {
        if (questoes == null || questoes.isEmpty()) {
            return new TrainingResult(null, null, null, null, List.of(), null, "Nenhuma questão enviada.");
        }
        String stamp = LocalDate.now().toString().replace("-", "") + "-" + System.currentTimeMillis() % 100000;
        List<QuestionDraft> drafts = questoes.stream().map(TrainingQuestion::toDraft).toList();
        byte[] json = QuestionDraft.toJson(titulo, banca, "claude-" + stamp, "Treino no Claude", drafts);
        ImportPreview preview = importService.previewJson("treino-claude-" + stamp + ".json", json);
        List<String> warnings = new ArrayList<>(preview.getFileErrors());
        for (PreviewItem item : preview.getItems()) {
            item.getErrors().forEach(e -> warnings.add(item.getSource().getLabel() + ": " + e));
            item.getWarnings().stream().filter(w -> !w.startsWith("Sem resposta"))
                    .forEach(w -> warnings.add(item.getSource().getLabel() + ": " + w));
        }
        if (!preview.getFileErrors().isEmpty()) {
            importService.discard(preview.getToken());
            return new TrainingResult(null, null, null, null, warnings, null, "O treino não pôde ser lido.");
        }
        ImportOutcome outcome = importService.confirm(preview.getToken(), new ImportOptions(titulo, false, false, true));
        if (outcome.error() != null) {
            return new TrainingResult(null, null, null, null, warnings, null, outcome.error());
        }
        if (outcome.trainingSessionId() == null) {
            return new TrainingResult(outcome.created(), outcome.skipped(), 0, 0, warnings,
                    baseUrl + "/import/result/" + outcome.importJobId(),
                    "Nenhuma questão tinha 'suaResposta': as questões foram gravadas, mas sem sessão de treino.");
        }
        StudySession session = sessionService.getSession(outcome.trainingSessionId());
        return new TrainingResult(outcome.created(), outcome.skipped(), session.getCorrectCount(),
                session.getWrongCount(), warnings, baseUrl + "/resultado/" + session.getId(), null);
    }

    // ---- rotina do dia ----

    public record TaskView(Long id, String titulo, String disciplina, LocalDate dia, boolean atrasada,
                           Integer minutosPlanejados, String descricao, String link,
                           List<LessonService.Linked> aulasEQuizzes) {
    }

    @McpTool(name = "tarefas_de_hoje",
            description = "Tarefas do cronograma para hoje e as atrasadas ainda abertas (sem a tarefa de revisão; "
                    + "para ela use revisoes_de_hoje).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<TaskView> todayTasks() {
        LocalDate today = LocalDate.now();
        List<StudyTask> tasks = taskService.dueBy(today).stream().filter(t -> !t.isReview()).toList();
        var linked = lessonService.forTasks(tasks.stream().map(StudyTask::getId).toList());
        return tasks.stream()
                .map(t -> new TaskView(t.getId(), t.getTitle(), t.getSubject(), t.getPlannedDate(), t.isOverdue(),
                        t.getEstimatedMinutes(), t.getDescription(), baseUrl + "/estudos/cronograma/" + t.getId() + "/editar",
                        linked.getOrDefault(t.getId(), List.of()).stream()
                                .map(l -> new LessonService.Linked(l.tipo(), l.id(), l.titulo(), l.andamento(),
                                        l.concluido(), baseUrl + l.link())).toList()))
                .toList();
    }

    public record TaskDone(Long id, String titulo, Integer minutos, LocalDate proximaRevisao) {
    }

    @McpTool(name = "concluir_tarefa",
            description = "Marca uma tarefa do cronograma como concluída (id vindo de tarefas_de_hoje), opcionalmente "
                    + "com os minutos dedicados. Concluir agenda a primeira revisão espaçada do assunto.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public TaskDone completeTask(
            @McpToolParam(description = "Id da tarefa.") Long id,
            @McpToolParam(description = "Minutos dedicados, se o usuário informou.", required = false) Integer minutos) {
        StudyTask task = taskService.setDone(id, true, minutos);
        if (task.isReview()) {
            spacedRepetition.syncReviewTask(LocalDate.now());
        }
        return new TaskDone(task.getId(), task.getTitle(), task.getSpentMinutes(), task.getNextReviewDate());
    }

    public record ReviewView(Long id, String assunto, String disciplina, LocalDate estudadoEm, LocalDate venceuEm,
                             int etapa, String materialLink) {
    }

    @McpTool(name = "revisoes_de_hoje",
            description = "Assuntos da revisão espaçada que vencem hoje (ou já venceram). Para cada um, faça perguntas "
                    + "rápidas ao usuário e depois chame marcar_revisao com lembrei=true ou false.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<ReviewView> todayReviews() {
        LocalDate today = LocalDate.now();
        spacedRepetition.syncReviewTask(today);
        return spacedRepetition.due(today).stream()
                .map(t -> new ReviewView(t.getId(), t.getTitle(), t.getSubject(), t.getPlannedDate(),
                        t.getNextReviewDate(), t.getCurrentStep(),
                        t.getPageId() == null ? null : baseUrl + "/estudos/paginas/" + t.getPageId()))
                .toList();
    }

    public record ReviewMarked(Long id, String assunto, boolean lembrou, LocalDate proximaRevisao,
                               int revisoesRestantesHoje) {
    }

    @McpTool(name = "marcar_revisao",
            description = "Registra o resultado da revisão de um assunto (id vindo de revisoes_de_hoje): lembrei=true "
                    + "avança para o próximo intervalo; false volta para amanhã. Quando não sobra assunto, a tarefa "
                    + "de revisão do dia some da lista.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ReviewMarked markReview(
            @McpToolParam(description = "Id do assunto.") Long id,
            @McpToolParam(description = "true se o usuário lembrou bem; false se esqueceu ou errou.") boolean lembrei) {
        LocalDate today = LocalDate.now();
        StudyTask task = lembrei ? spacedRepetition.remembered(id, today) : spacedRepetition.forgot(id, today);
        spacedRepetition.syncReviewTask(today);
        return new ReviewMarked(task.getId(), task.getTitle(), lembrei, task.getNextReviewDate(),
                spacedRepetition.due(today).size());
    }

    public record SummaryAppended(Long paginaId, String titulo, String link) {
    }

    @McpTool(name = "anexar_ao_resumo_do_dia",
            description = "Acrescenta um texto ao fim do resumo do dia (cria o resumo se ainda não existe). Aceita "
                    + "Markdown simples: '## título', '### subtítulo', listas com '- ' (recuo de 2 espaços = "
                    + "sublista), '**negrito**', '*itálico*' e parágrafos separados por linha em branco.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public SummaryAppended appendToDaySummary(
            @McpToolParam(description = "Texto a acrescentar.") String texto,
            @McpToolParam(description = "Dia no formato AAAA-MM-DD; vazio = hoje.", required = false) String data) {
        LocalDate date = data == null || data.isBlank() ? LocalDate.now() : LocalDate.parse(data.trim());
        StudyPage page = daySummaryService.open(date);
        String content = (page.getContent() == null ? "" : page.getContent()) + com.pdf2questao.web.SimpleMarkdown.toHtml(texto);
        pageService.saveContent(page.getId(), page.getTitle(), content, page.getVersion());
        return new SummaryAppended(page.getId(), page.getTitle(), baseUrl + "/estudos/paginas/" + page.getId());
    }

    // ---- apoio ----

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT).trim();
    }

    private static String plain(String text) {
        return McpText.plain(text);
    }

    private static String clip(String text) {
        return McpText.clip(text, MAX_TEXT);
    }
}
