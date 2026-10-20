package com.pdf2questao.mcp;

import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator.ScoreResult;
import com.pdf2questao.session.Attempt;
import com.pdf2questao.session.AttemptRepository;
import com.pdf2questao.session.ReviewItem;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionRepository;
import com.pdf2questao.session.StudySessionService;
import com.pdf2questao.study.StudyPage;
import com.pdf2questao.study.StudyPageRepository;
import com.pdf2questao.study.StudyTask;
import com.pdf2questao.study.StudyTaskService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Ferramentas de analise (erros, resultado de sessao, historico), leitura dos resumos e planejamento de tarefas. */
@Component
public class InsightMcpTools {

    private static final int MAX_PAGE_CHARS = 15000;

    private final QuestionRepository questionRepository;
    private final AttemptRepository attemptRepository;
    private final StudySessionRepository sessionRepository;
    private final StudySessionService sessionService;
    private final StudyPageRepository pageRepository;
    private final StudyTaskService taskService;
    private final String baseUrl;

    public InsightMcpTools(QuestionRepository questionRepository, AttemptRepository attemptRepository,
                           StudySessionRepository sessionRepository, StudySessionService sessionService,
                           StudyPageRepository pageRepository, StudyTaskService taskService,
                           @Value("${server.port:8080}") int port) {
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.pageRepository = pageRepository;
        this.taskService = taskService;
        this.baseUrl = "http://localhost:" + port;
    }

    // ---- erros ----

    public record SubjectErrors(String disciplina, long erros, long acertosNoChute) {
    }

    public record ErrorSummary(long totalDeErros, long totalDeAcertosNoChute, List<SubjectErrors> porDisciplina) {
    }

    @McpTool(name = "resumo_de_erros",
            description = "Quantos erros (caderno de erros) e acertos no chute há em cada disciplina, sem trazer as "
                    + "questões. Use antes de listar_erros para escolher a disciplina.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ErrorSummary errorSummary() {
        Map<String, long[]> by = new LinkedHashMap<>();
        questionRepository.errorNotebookBySubject().forEach(r ->
                by.computeIfAbsent((String) r[0], k -> new long[2])[0] = ((Number) r[1]).longValue());
        questionRepository.luckyGuessesBySubject().forEach(r ->
                by.computeIfAbsent((String) r[0], k -> new long[2])[1] = ((Number) r[1]).longValue());
        List<SubjectErrors> list = by.entrySet().stream()
                .map(e -> new SubjectErrors(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(SubjectErrors::erros).reversed()).toList();
        return new ErrorSummary(list.stream().mapToLong(SubjectErrors::erros).sum(),
                list.stream().mapToLong(SubjectErrors::acertosNoChute).sum(), list);
    }

    // ---- resultado e historico ----

    public record SubjectResult(String disciplina, int acertos, int erros, int emBranco, int chutes,
                                Long segundosPorQuestao) {
    }

    public record SlowQuestion(int numero, Long questaoId, String disciplina, long segundos, Boolean acertou) {
    }

    public record WrongQuestion(int numero, Long questaoId, String disciplina, String assunto, String suaResposta,
                                String respostaCorreta, boolean chute) {
    }

    public record SessionResult(Long sessaoId, String nome, String tipo, LocalDate data, int questoes, int acertos,
                                int erros, int emBranco, Double percentual, Long minutos, long foraDoTempo,
                                Double notaProjetada, Double notaMaxima, Boolean passaria,
                                List<SubjectResult> porDisciplina, List<SlowQuestion> maisLentas,
                                List<WrongQuestion> erradas, String link) {
    }

    @McpTool(name = "ver_resultado",
            description = "Análise de uma sessão finalizada (simulado, prova, treino): acertos/erros/brancos e chutes "
                    + "por disciplina, tempo por questão, as 5 mais lentas, as erradas e a projeção do edital. Sem "
                    + "id, a última sessão finalizada.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public SessionResult sessionResult(
            @McpToolParam(description = "Id da sessão (vazio = a última).", required = false) Long sessaoId) {
        StudySession session = sessaoId != null ? sessionService.getSession(sessaoId)
                : sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Nenhuma sessão finalizada ainda."));
        if (session.getFinishedAt() == null) {
            throw new IllegalArgumentException("A sessão " + session.getId() + " ainda não foi finalizada.");
        }
        List<ReviewItem> review = sessionService.review(session.getId());
        Map<Long, Attempt> attempts = attemptRepository.findBySessionId(session.getId()).stream()
                .collect(Collectors.toMap(Attempt::getQuestionId, Function.identity(), (a, b) -> a));
        Map<String, int[]> bySubject = new LinkedHashMap<>();   // acertos, erros, branco, chutes, n com tempo
        Map<String, long[]> time = new LinkedHashMap<>();
        List<SlowQuestion> slow = new ArrayList<>();
        List<WrongQuestion> wrong = new ArrayList<>();
        for (ReviewItem r : review) {
            if (r.question() == null) {
                continue;
            }
            String subject = r.question().getSubject() == null ? "(sem disciplina)" : r.question().getSubject();
            int[] c = bySubject.computeIfAbsent(subject, k -> new int[4]);
            if (r.selectedAnswer() == null) {
                c[2]++;
            } else if (Boolean.TRUE.equals(r.correct())) {
                c[0]++;
            } else if (Boolean.FALSE.equals(r.correct())) {
                c[1]++;
                wrong.add(new WrongQuestion(r.index(), r.question().getId(), subject, r.question().getTopic(),
                        r.selectedAnswer(), r.question().getCorrectAnswer(), Boolean.TRUE.equals(r.guessed())));
            }
            if (Boolean.TRUE.equals(r.guessed())) {
                c[3]++;
            }
            Attempt a = attempts.get(r.question().getId());
            if (a != null && a.getElapsedMilliseconds() != null && a.getElapsedMilliseconds() > 0) {
                long[] t = time.computeIfAbsent(subject, k -> new long[2]);
                t[0] += a.getElapsedMilliseconds();
                t[1]++;
                slow.add(new SlowQuestion(r.index(), r.question().getId(), subject, a.getElapsedMilliseconds() / 1000,
                        r.correct()));
            }
        }
        List<SubjectResult> subjects = bySubject.entrySet().stream().map(e -> {
            long[] t = time.get(e.getKey());
            int[] c = e.getValue();
            return new SubjectResult(e.getKey(), c[0], c[1], c[2], c[3], t == null ? null : t[0] / t[1] / 1000);
        }).sorted(Comparator.comparingDouble(s -> s.acertos() / (double) Math.max(1, s.acertos() + s.erros() + s.emBranco())))
                .toList();
        slow.sort(Comparator.comparingLong(SlowQuestion::segundos).reversed());
        ScoreResult score = sessionService.scoreResult(session);
        int blank = (int) review.stream().filter(r -> r.selectedAnswer() == null).count();
        return new SessionResult(session.getId(), session.getBlockName() != null ? session.getBlockName()
                : session.getMode().label(), session.getMode().label(), session.getFinishedAt().toLocalDate(),
                review.size(), session.getCorrectCount(), session.getWrongCount(), blank,
                session.getPercent() == null ? null : Math.round(session.getPercent() * 10) / 10.0,
                session.getTotalElapsedMillis() == null ? null : session.getTotalElapsedMillis() / 60000,
                sessionService.overtimeCount(session.getId()),
                score == null || score.projectedTotal() == null ? null : Math.round(score.projectedTotal() * 10) / 10.0,
                score == null ? null : score.maxTotal(), score == null ? null : score.projectedPassed(),
                subjects, slow.stream().limit(5).toList(), wrong.stream().limit(40).toList(),
                baseUrl + "/resultado/" + session.getId());
    }

    public record GroupHistory(String prova, Double pontosProjetados, Double minimo, Boolean bateMinimo) {
    }

    public record HistoryRow(Long sessaoId, String nome, LocalDate data, int acertos, int questoes,
                             Double notaProjetada, Double notaMaxima, Boolean passaria, Double cobertura,
                             List<GroupHistory> provas) {
    }

    @McpTool(name = "historico_simulados",
            description = "Simulados finalizados com perfil de pontuação, do mais antigo ao mais recente: nota "
                    + "projetada contra os mínimos de cada prova. Use para ver a evolução.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<HistoryRow> examHistory() {
        List<HistoryRow> rows = new ArrayList<>();
        for (StudySession s : sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc()) {
            ScoreResult score = s.getScoreReport() == null ? null : sessionService.scoreResult(s);
            if (score == null) {
                continue;
            }
            rows.add(new HistoryRow(s.getId(), s.getBlockName(), s.getFinishedAt().toLocalDate(), s.getCorrectCount(),
                    s.questionIds().size(), round(score.projectedTotal()), score.maxTotal(), score.projectedPassed(),
                    round(score.coverage()), score.groups().stream().map(g -> new GroupHistory(g.name(),
                    round(g.projectedPoints()), g.minScore(), g.projectedMinMet())).toList()));
        }
        java.util.Collections.reverse(rows);
        return rows;
    }

    // ---- paginas ----

    public record PageHit(Long id, String titulo, String disciplina, LocalDate atualizadaEm, String trecho, String link) {
    }

    @McpTool(name = "buscar_paginas",
            description = "Procura nas páginas e resumos de estudo do usuário (título e texto). Devolve um trecho de "
                    + "cada uma; leia a página inteira com ler_pagina.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public List<PageHit> searchPages(
            @McpToolParam(description = "Termo a procurar, ex.: \"anualidade\".") String termo,
            @McpToolParam(description = "Máximo de páginas (padrão 10).", required = false) Integer limite) {
        String term = termo == null ? "" : termo.strip();
        if (term.isEmpty()) {
            throw new IllegalArgumentException("Informe o termo.");
        }
        int limit = Math.max(1, Math.min(limite == null ? 10 : limite, 30));
        return pageRepository.search(term).stream().limit(limit).map(p -> {
            String text = McpText.plain(p.getContent());
            int at = text == null ? -1 : text.toLowerCase().indexOf(term.toLowerCase());
            String snippet = text == null ? null : at < 0 ? McpText.clip(text, 240)
                    : (at > 120 ? "…" : "") + McpText.clip(text.substring(Math.max(0, at - 120)), 300);
            return new PageHit(p.getId(), p.getDisplayTitle(), p.getSubject(),
                    p.getUpdatedAt() == null ? null : p.getUpdatedAt().toLocalDate(), snippet,
                    baseUrl + "/estudos/paginas/" + p.getId());
        }).toList();
    }

    public record PageText(Long id, String titulo, String disciplina, String texto, boolean cortado, String link) {
    }

    @McpTool(name = "ler_pagina",
            description = "Texto de uma página de estudo (resumo) do usuário, para criar questões ou revisar a partir "
                    + "do que ele anotou.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public PageText readPage(@McpToolParam(description = "Id da página (buscar_paginas).") Long id) {
        StudyPage page = pageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Página " + id + " não existe."));
        String text = McpText.plain(page.getContent());
        return new PageText(page.getId(), page.getDisplayTitle(), page.getSubject(), McpText.clip(text, MAX_PAGE_CHARS),
                text != null && text.length() > MAX_PAGE_CHARS, baseUrl + "/estudos/paginas/" + page.getId());
    }

    // ---- cronograma ----

    public record CreatedTask(Long id, String titulo, LocalDate dia, String link) {
    }

    @McpTool(name = "criar_tarefa",
            description = "Agenda uma tarefa no cronograma do usuário (ex.: reforço numa disciplina fraca depois de um "
                    + "simulado). Confirme com o usuário antes de agendar várias.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public CreatedTask createTask(
            @McpToolParam(description = "O que estudar.") String titulo,
            @McpToolParam(description = "Dia (AAAA-MM-DD); vazio = hoje.", required = false) String dia,
            @McpToolParam(description = "Disciplina.", required = false) String disciplina,
            @McpToolParam(description = "Minutos planejados.", required = false) Integer minutos,
            @McpToolParam(description = "Detalhes: tópicos, questões, links.", required = false) String descricao) {
        LocalDate date = McpText.blank(dia) ? LocalDate.now() : LocalDate.parse(dia.strip());
        StudyTask task = taskService.create(new StudyTaskService.TaskForm(titulo, descricao, disciplina, date, minutos,
                null, null));
        return new CreatedTask(task.getId(), task.getTitle(), task.getPlannedDate(),
                baseUrl + "/estudos/cronograma/" + task.getId() + "/editar");
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10) / 10.0;
    }
}
