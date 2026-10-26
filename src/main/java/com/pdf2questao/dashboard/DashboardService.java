package com.pdf2questao.dashboard;

import com.pdf2questao.block.BlockService;
import com.pdf2questao.block.StudyBlock;
import com.pdf2questao.dashboard.DashboardData.BlockCard;
import com.pdf2questao.dashboard.DashboardData.BlockPanel;
import com.pdf2questao.dashboard.DashboardData.BlockSession;
import com.pdf2questao.dashboard.DashboardData.OpenSession;
import com.pdf2questao.dashboard.DashboardData.Overview;
import com.pdf2questao.dashboard.DashboardData.ProvaCard;
import com.pdf2questao.dashboard.DashboardData.ProvaGroup;
import com.pdf2questao.dashboard.DashboardData.ProvaPanel;
import com.pdf2questao.dashboard.DashboardData.SessionRow;
import com.pdf2questao.dashboard.DashboardData.SubjectProgress;
import com.pdf2questao.dashboard.DashboardData.Today;
import com.pdf2questao.guess.GuessDiagnosticService;
import com.pdf2questao.importer.Banca;
import com.pdf2questao.importer.ImportJob;
import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator.ScoreResult;
import com.pdf2questao.scoring.ScoringProfile;
import com.pdf2questao.scoring.ScoringProfileService;
import com.pdf2questao.session.AttemptRepository;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionRepository;
import com.pdf2questao.session.StudySessionService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Monta o dashboard separado por prova: a visao geral lista cada prova (agrupada por banca) e cada bloco
 * com seu desempenho, e cada prova/bloco tem um painel proprio. Nada aqui mistura questoes de provas
 * diferentes, exceto o resumo recolhido "por disciplina" da visao geral.
 */
@Service
public class DashboardService {

    private static final int RECENT_SESSIONS = 8;

    private final QuestionRepository questionRepository;
    private final AttemptRepository attemptRepository;
    private final StudySessionRepository sessionRepository;
    private final ImportJobRepository importJobRepository;
    private final BlockService blockService;
    private final StudySessionService sessionService;
    private final ScoringProfileService profileService;
    private final GuessDiagnosticService guessService;
    private final com.pdf2questao.concurso.Workspace workspace;

    public DashboardService(QuestionRepository questionRepository, AttemptRepository attemptRepository,
                            StudySessionRepository sessionRepository, ImportJobRepository importJobRepository,
                            BlockService blockService, StudySessionService sessionService,
                            ScoringProfileService profileService, GuessDiagnosticService guessService,
                            com.pdf2questao.concurso.Workspace workspace) {
        this.workspace = workspace;
        this.guessService = guessService;
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.sessionRepository = sessionRepository;
        this.importJobRepository = importJobRepository;
        this.blockService = blockService;
        this.sessionService = sessionService;
        this.profileService = profileService;
    }

    // ---- visao geral ----

    public Overview overview() {
        Map<Long, Long> questionCounts = new HashMap<>();
        questionRepository.countsByJob().forEach(r -> questionCounts.put(num(r[0]), num(r[1])));
        Map<Long, long[]> totals = new HashMap<>();
        attemptRepository.totalsByJob().forEach(r -> totals.put(num(r[0]), new long[]{num(r[1]), num(r[2]), num(r[3])}));

        Map<Long, List<Long>> sessionIdsByJob = new HashMap<>();
        attemptRepository.sessionsByJob().forEach(r ->
                sessionIdsByJob.computeIfAbsent(num(r[0]), k -> new ArrayList<>()).add(num(r[1])));
        Map<Long, StudySession> sessions = sessionRepository.findAll().stream()
                .collect(Collectors.toMap(StudySession::getId, s -> s));

        List<ProvaCard> cards = new ArrayList<>();
        long visibleQuestions = 0;
        for (ImportJob job : importJobRepository.findAll().stream().filter(j -> workspace.matchesOrShared(j.getConcursoId()))
                .sorted(Comparator.comparing(ImportJob::getId)).toList()) {
            long questions = questionCounts.getOrDefault(job.getId(), 0L);
            if (questions == 0) {
                continue;
            }
            visibleQuestions += questions;
            long[] t = totals.getOrDefault(job.getId(), new long[3]);
            List<StudySession> jobSessions = sessionIdsByJob.getOrDefault(job.getId(), List.of()).stream()
                    .map(sessions::get).filter(Objects::nonNull).toList();
            LocalDateTime last = jobSessions.stream().map(StudySession::getStartedAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            cards.add(new ProvaCard(job, questions, t[0], t[1], t[2], jobSessions.size(), last));
        }

        Map<String, List<ProvaCard>> byBanca = new LinkedHashMap<>();
        cards.stream().sorted(Comparator.comparing((ProvaCard c) -> bancaLabel(c.job()), String.CASE_INSENSITIVE_ORDER))
                .forEach(c -> byBanca.computeIfAbsent(bancaLabel(c.job()), k -> new ArrayList<>()).add(c));
        List<ProvaGroup> groups = byBanca.entrySet().stream().map(e -> new ProvaGroup(e.getKey(), e.getValue())).toList();

        List<StudyBlock> allBlocks = blockService.visible();
        Map<Long, Integer> blockCounts = blockService.questionCounts(allBlocks);
        List<BlockCard> blocks = allBlocks.stream()
                .map(b -> blockCard(b, blockCounts.getOrDefault(b.getId(), 0), sessions.values())).toList();

        List<SessionRow> recent = sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc().stream()
                .filter(s -> workspace.matches(s.getConcursoId())).limit(RECENT_SESSIONS)
                .map(s -> new SessionRow(s, s.getCorrectCount(), s.getWrongCount())).toList();

        List<OpenSession> open = openSessions();

        Long concurso = workspace.currentId();
        Object[] answers = attemptRepository.totals(concurso).get(0);
        long correct = num(answers[0]);
        long wrong = num(answers[1]);
        return new Overview(cards.size(), visibleQuestions, attemptRepository.countDistinctQuestionsAnswered(concurso),
                correct, wrong, groups, blocks, recent, mapStats(attemptRepository.statsBySubject(concurso)),
                guessService.counts(null), open, errorNotebookCount());
    }

    /** O lado "questoes" da tela Hoje, sem as estatisticas pesadas do painel. */
    public Today today() {
        List<OpenSession> open = openSessions();
        StudySession last = lastScored();
        return new Today(open, errorNotebookCount(), last,
                last == null ? null : sessionService.scoreResult(last));
    }

    /** Ate 3 sessoes em aberto do concurso em vigor. */
    private List<OpenSession> openSessions() {
        if (workspace.currentId() == null) {
            return sessionRepository.findTop3ByFinishedAtIsNullOrderByStartedAtDesc().stream()
                    .map(s -> new OpenSession(s, sessionService.resumeIndex(s))).toList();
        }
        return sessionRepository.findAll().stream()
                .filter(s -> s.getFinishedAt() == null && workspace.matches(s.getConcursoId()))
                .sorted(Comparator.comparing(StudySession::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(3).map(s -> new OpenSession(s, sessionService.resumeIndex(s))).toList();
    }

    /** Ultimo simulado finalizado com nota do perfil, no concurso em vigor. */
    public StudySession lastScored() {
        if (workspace.currentId() == null) {
            return sessionRepository.findFirstByFinishedAtIsNotNullAndScoreReportIsNotNullOrderByFinishedAtDesc()
                    .orElse(null);
        }
        return sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc().stream()
                .filter(s -> s.getScoreReport() != null && workspace.matches(s.getConcursoId()))
                .max(Comparator.comparing(StudySession::getFinishedAt)).orElse(null);
    }

    /** Questoes no caderno de erros do concurso em vigor. */
    public long errorNotebookCount() {
        return workspace.currentId() == null ? questionRepository.countErrorNotebook()
                : questionRepository.findErrorNotebook(workspace.currentId()).size();
    }

    /** Desempenho por disciplina nas sessoes do concurso em vigor. */
    public List<SubjectStat> subjectStats() {
        return mapStats(attemptRepository.statsBySubject(workspace.currentId()));
    }

    private BlockCard blockCard(StudyBlock block, int questions, java.util.Collection<StudySession> allSessions) {
        List<StudySession> finished = allSessions.stream()
                .filter(s -> block.getId().equals(s.getBlockId()) && s.getFinishedAt() != null)
                .sorted(Comparator.comparing(StudySession::getStartedAt).reversed()).toList();
        StudySession last = finished.isEmpty() ? null : finished.get(0);
        Double best = finished.stream().map(StudySession::getScoreTotal).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        Boolean passed = null;
        if (last != null) {
            ScoreResult result = sessionService.scoreResult(last);
            passed = result == null ? null : result.passed();
        }
        return new BlockCard(block, questions, finished.size(),
                last == null ? null : last.getScoreTotal(), passed, best, last == null ? null : last.getStartedAt());
    }

    // ---- painel de uma prova ----

    public ProvaPanel prova(Long jobId) {
        ImportJob job = importJobRepository.findById(jobId).orElseThrow();
        List<Question> questions = questionRepository.findByImportJobId(jobId);
        List<SubjectProgress> subjects = attemptRepository.subjectProgressForJob(jobId).stream()
                .map(r -> new SubjectProgress((String) r[0], num(r[1]), num(r[2]), num(r[3]), num(r[4]))).toList();

        long answered = subjects.stream().mapToLong(SubjectProgress::answered).sum();
        long correct = subjects.stream().mapToLong(SubjectProgress::correct).sum();
        long wrong = subjects.stream().mapToLong(SubjectProgress::wrong).sum();

        Map<Long, long[]> stats = new HashMap<>();
        attemptRepository.sessionStatsForJob(jobId).forEach(r -> stats.put(num(r[0]), new long[]{num(r[1]), num(r[2])}));
        List<SessionRow> rows = sessionRepository.findAllById(stats.keySet()).stream()
                .sorted(Comparator.comparing(StudySession::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .map(s -> new SessionRow(s, stats.get(s.getId())[0], stats.get(s.getId())[1])).toList();

        return new ProvaPanel(job, questions.size(), answered, correct, wrong,
                questionRepository.findErrorNotebookByJob(jobId).size(),
                questions.stream().filter(Question::isNeedsReview).count(), subjects,
                mapStats(attemptRepository.statsByTopicForJob(jobId)), rows, guessService.counts(jobId));
    }

    // ---- painel de um bloco ----

    public BlockPanel bloco(Long blockId) {
        StudyBlock block = blockService.find(blockId).orElseThrow();
        List<BlockSession> sessions = sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc().stream()
                .filter(s -> blockId.equals(s.getBlockId()))
                .map(s -> new BlockSession(s, sessionService.scoreResult(s))).toList();
        String profile = block.getScoringProfileId() == null ? null
                : profileService.find(block.getScoringProfileId()).map(ScoringProfile::getName).orElse(null);
        return new BlockPanel(block, blockService.resolve(blockId).questions().size(), profile, sessions);
    }

    // ---- helpers ----

    private String bancaLabel(ImportJob job) {
        if (job.getBanca() == null) {
            return "Outras";
        }
        try {
            return Banca.valueOf(job.getBanca()).getLabel();
        } catch (IllegalArgumentException e) {
            return job.getBanca();
        }
    }

    private List<SubjectStat> mapStats(List<Object[]> rows) {
        return rows.stream().map(r -> new SubjectStat((String) r[0], num(r[1]), num(r[2]))).toList();
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
