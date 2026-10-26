package com.pdf2questao.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.block.BlockService;
import com.pdf2questao.block.StudyBlock;
import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionCategory;
import com.pdf2questao.question.QuestionRepository;
import com.pdf2questao.scoring.ScoringCalculator;
import com.pdf2questao.scoring.ScoringProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StudySessionService {

    private final StudySessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final BlockService blockService;
    private final ScoringProfileService profileService;
    private final com.pdf2questao.concurso.Workspace workspace;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StudySessionService(StudySessionRepository sessionRepository, AttemptRepository attemptRepository,
                                QuestionRepository questionRepository, BlockService blockService,
                                ScoringProfileService profileService, com.pdf2questao.concurso.Workspace workspace) {
        this.workspace = workspace;
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
        this.blockService = blockService;
        this.profileService = profileService;
    }

    /** Inicia uma sessao com as questoes do bloco (origens, faixas e exclusoes ja aplicadas). */
    public StudySession startBloco(Long blockId) {
        StudyBlock block = blockService.find(blockId).orElseThrow();
        return startBloco(blockId, block.getTimeLimitMinutes(), false);
    }

    /**
     * Inicio personalizado: {@code timeLimit} substitui o tempo do bloco (null = sem tempo); {@code shuffle}
     * embaralha a ordem das questoes.
     */
    public StudySession startBloco(Long blockId, Integer timeLimit, boolean shuffle) {
        StudyBlock block = blockService.find(blockId).orElseThrow();
        List<Long> ids = blockService.questionIds(blockId);
        if (ids.isEmpty()) {
            throw new EmptySessionException("O bloco '" + block.getName() + "' não tem questões.");
        }
        if (shuffle) {
            ids = new ArrayList<>(ids);
            Collections.shuffle(ids);
        }
        StudySession session = createSession(SessionMode.SIMULADO, ids);
        session.setBlockId(block.getId());
        session.setBlockName(block.getName());
        session.setScoringProfileId(block.getScoringProfileId());
        if (block.getConcursoId() != null) {
            session.setConcursoId(block.getConcursoId());
        }
        session.setTimeLimitMinutes(timeLimit == null || timeLimit <= 0 ? null : timeLimit);
        return sessionRepository.save(session);
    }

    /** Sessao do bloco que foi iniciada e ainda nao terminou (a mais recente), se houver. */
    public java.util.Optional<StudySession> openBlocoSession(Long blockId) {
        return sessionRepository.findFirstByBlockIdAndFinishedAtIsNullOrderByStartedAtDesc(blockId);
    }

    /** Ids das questoes que ja tem resposta na sessao (para o mapa de questoes do resolver). */
    public java.util.Set<Long> answeredQuestionIds(Long sessionId) {
        return attemptRepository.findBySessionId(sessionId).stream()
                .map(Attempt::getQuestionId).collect(java.util.stream.Collectors.toSet());
    }

    /** Ids dos blocos que tem sessao aberta (uma consulta para a lista toda de blocos). */
    public java.util.Set<Long> blocksWithOpenSession() {
        return sessionRepository.findByBlockIdIsNotNullAndFinishedAtIsNull().stream()
                .map(StudySession::getBlockId).collect(java.util.stream.Collectors.toSet());
    }

    /** Indice da primeira questao ainda sem resposta na sessao (a ultima, se todas ja foram respondidas). */
    public int resumeIndex(StudySession session) {
        List<Long> ids = session.questionIds();
        java.util.Set<Long> answered = attemptRepository.findBySessionId(session.getId()).stream()
                .map(Attempt::getQuestionId).collect(java.util.stream.Collectors.toSet());
        for (int i = 0; i < ids.size(); i++) {
            if (!answered.contains(ids.get(i))) {
                return i;
            }
        }
        return Math.max(0, ids.size() - 1);
    }

    /**
     * Resultado do perfil de pontuacao congelado no fim da sessao (null se a sessao nao usa perfil). Relatorio
     * gravado antes da projecao para o edital e recalculado (e gravado de novo) na primeira leitura.
     */
    public ScoringCalculator.ScoreResult scoreResult(StudySession session) {
        if (session.getScoreReport() == null) {
            return null;
        }
        ScoringCalculator.ScoreResult result;
        try {
            result = objectMapper.readValue(session.getScoreReport(), ScoringCalculator.ScoreResult.class);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (result.coverage() == null && session.getFinishedAt() != null) {
            recount(session);
            sessionRepository.save(session);
            return scoreResult(session).coverage() == null ? result : scoreResult(session);
        }
        return result;
    }

    public StudySession startPractice(String subject, String topic, Integer year, String organization,
                                       Long importJobId, int quantity, boolean keepExamOrder) {
        List<Long> ids = keepExamOrder
                ? questionRepository.findOrderedIds(subject, topic, year, organization, importJobId, quantity)
                : questionRepository.findRandomIds(subject, topic, year, organization, importJobId, quantity);
        return createSession(SessionMode.PRATICA, ids);
    }

    public StudySession startProva(Long importJobId) {
        return startProva(importJobId, null);
    }

    /** Prova importada inteira, na ordem; {@code timeLimit} em minutos (null = sem tempo). */
    public StudySession startProva(Long importJobId, Integer timeLimit) {
        List<Long> ids = questionRepository.findByImportJobId(importJobId).stream()
                .sorted(Comparator.comparing(Question::getQuestionNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Question::getId)
                .toList();
        StudySession session = createSession(SessionMode.PROVA, ids);
        if (timeLimit != null && timeLimit > 0) {
            session.setTimeLimitMinutes(timeLimit);
            session = sessionRepository.save(session);
        }
        return session;
    }

    public StudySession startRefazer(List<Long> questionIds) {
        return createSession(SessionMode.REFAZER, questionIds);
    }

    private StudySession createSession(SessionMode mode, List<Long> ids) {
        if (ids.isEmpty()) {
            throw new EmptySessionException("Nenhuma questão encontrada com esses filtros.");
        }
        StudySession session = new StudySession();
        session.setMode(mode);
        session.setQuestionIdsOrder(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        session.setStartedAt(LocalDateTime.now());
        session.setConcursoId(workspace.currentId());
        return sessionRepository.save(session);
    }

    /** Resposta que o usuario deu fora do sistema; {@code guess} null = nao informado. */
    public record TrainingAnswer(Long questionId, String selectedAnswer, Boolean guess) {
    }

    /**
     * Registra um treino feito fora do sistema (ex.: questoes resolvidas no chat) como sessao ja finalizada: as
     * respostas entram no caderno de erros, nos chutes e nas estatisticas como as de qualquer sessao.
     */
    @Transactional
    public StudySession recordTraining(String name, List<TrainingAnswer> answers) {
        StudySession session = createSession(SessionMode.TREINO,
                answers.stream().map(TrainingAnswer::questionId).toList());
        session.setBlockName(name);
        for (TrainingAnswer answer : answers) {
            answer(session.getId(), answer.questionId(), answer.selectedAnswer(), null, null, answer.guess());
        }
        return finish(session.getId());
    }

    /** Resposta ja dada nesta sessao a uma questao (para repor a alternativa marcada e o "E chute?"). */
    public java.util.Optional<Attempt> attemptOf(Long sessionId, Long questionId) {
        return attemptRepository.findBySessionIdAndQuestionId(sessionId, questionId);
    }

    /** Respostas dadas depois do tempo limite do bloco. */
    public long overtimeCount(Long sessionId) {
        return attemptRepository.findBySessionId(sessionId).stream()
                .filter(a -> Boolean.TRUE.equals(a.getOvertime())).count();
    }

    public StudySession getSession(Long id) {
        return sessionRepository.findById(id).orElseThrow();
    }

    public void answer(Long sessionId, Long questionId, String selectedAnswer, Long elapsedMillis) {
        answer(sessionId, questionId, selectedAnswer, elapsedMillis, null, null);
    }

    public void answer(Long sessionId, Long questionId, String selectedAnswer, Long elapsedMillis, Boolean guess) {
        answer(sessionId, questionId, selectedAnswer, elapsedMillis, null, guess);
    }

    /**
     * @param elapsedMillis tempo nesta questao desde o ultimo envio (soma ao que ja havia: voltar a questao
     *                      acumula, nao substitui).
     * @param sessionMillis tempo de cronometro da sessao desde o ultimo envio (sem pausas).
     * @param guess "E chute?"; null mantem o que ja estava marcado nesta resposta (ou nao informado).
     * @throws SessionFinishedException se a sessao ja foi finalizada (a nota dela esta congelada).
     */
    @Transactional
    public void answer(Long sessionId, Long questionId, String selectedAnswer, Long elapsedMillis,
                       Long sessionMillis, Boolean guess) {
        StudySession session = getSession(sessionId);
        if (session.getFinishedAt() != null) {
            throw new SessionFinishedException();
        }
        addSessionTime(session, sessionMillis);
        Question question = questionRepository.findById(questionId).orElseThrow();
        Attempt attempt = attemptRepository.findBySessionIdAndQuestionId(sessionId, questionId)
                .orElseGet(Attempt::new);
        LocalDateTime now = LocalDateTime.now();
        attempt.setSessionId(sessionId);
        attempt.setQuestionId(questionId);
        attempt.setSelectedAnswer(selectedAnswer);
        attempt.setAnsweredAt(now);
        attempt.setElapsedMilliseconds(addMillis(attempt.getElapsedMilliseconds(), elapsedMillis));
        if (session.getTimeLimitMinutes() != null) {
            attempt.setOvertime(session.activeMillisOrZero() > session.getTimeLimitMinutes() * 60_000L);
        }
        // Questao anulada conta ponto para todos, qualquer que seja a resposta.
        Boolean correct = question.isAnnulled() ? Boolean.TRUE
                : question.getCorrectAnswer() == null ? null
                : question.getCorrectAnswer().equalsIgnoreCase(selectedAnswer);
        attempt.setCorrect(correct);
        if (guess != null) {
            attempt.setGuessed(guess);
        }
        attemptRepository.save(attempt);
    }

    /**
     * Soma o tempo contado pelo navegador desde o ultimo envio (ao sair da questao e de tempos em tempos): o da
     * sessao sempre; o da questao so se ela ja tem resposta.
     */
    @Transactional
    public void addTime(Long sessionId, Long questionId, Long questionMillis, Long sessionMillis) {
        StudySession session = getSession(sessionId);
        if (session.getFinishedAt() != null) {
            return;
        }
        addSessionTime(session, sessionMillis);
        if (questionMillis != null && questionMillis > 0) {
            attemptRepository.findBySessionIdAndQuestionId(sessionId, questionId).ifPresent(attempt -> {
                attempt.setElapsedMilliseconds(addMillis(attempt.getElapsedMilliseconds(), questionMillis));
                attemptRepository.save(attempt);
            });
        }
    }

    private void addSessionTime(StudySession session, Long millis) {
        if (millis != null && millis > 0) {
            session.setActiveMillis(addMillis(session.getActiveMillis(), millis));
            sessionRepository.save(session);
        }
    }

    private static Long addMillis(Long current, Long more) {
        // Mais de 3 h num envio so e aba esquecida aberta, nao resolucao.
        long add = more == null ? 0 : Math.max(0, Math.min(more, 3 * 3_600_000L));
        return (current == null ? 0 : current) + add;
    }

    /**
     * Descarta uma sessao ainda aberta: apaga a sessao e as respostas dela (somem tambem do caderno de erros
     * e das estatisticas). Sessao ja finalizada nao e descartada.
     */
    @Transactional
    public void discard(Long sessionId) {
        StudySession session = getSession(sessionId);
        if (session.getFinishedAt() != null) {
            throw new IllegalStateException("Sessao ja finalizada nao pode ser descartada.");
        }
        attemptRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
    }

    /** Finaliza a sessao e congela a nota. Finalizar de novo (ex.: botao repetido) nao muda nada. */
    public StudySession finish(Long sessionId) {
        StudySession session = getSession(sessionId);
        if (session.getFinishedAt() != null) {
            return session;
        }
        session.setFinishedAt(LocalDateTime.now());
        recount(session);
        return sessionRepository.save(session);
    }

    /**
     * Recalcula a nota de sessoes ja finalizadas que tem resposta nestas questoes, depois de recorrigir as
     * respostas com o gabarito atual. Chamado quando o gabarito ou as anulacoes mudam.
     *
     * @return quantas sessoes foram recalculadas
     */
    @Transactional
    public int regrade(java.util.Collection<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return 0;
        }
        attemptRepository.regrade(questionIds);
        List<StudySession> sessions = sessionRepository.findAllById(
                attemptRepository.sessionIdsWithQuestions(questionIds));
        int count = 0;
        for (StudySession session : sessions) {
            if (session.getFinishedAt() != null) {
                recount(session);
                sessionRepository.save(session);
                count++;
            }
        }
        return count;
    }

    /** Contagens, tempo e notas da sessao a partir das respostas gravadas. */
    private void recount(StudySession session) {
        List<Attempt> attempts = attemptRepository.findBySessionId(session.getId());
        Map<Long, Question> sessionQuestions = questionRepository.findAllById(session.questionIds()).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        int correctCount = 0;
        int wrongCount = 0;
        long totalElapsed = 0;
        double nota = 0;

        for (Attempt attempt : attempts) {
            totalElapsed += attempt.getElapsedMilliseconds() != null ? attempt.getElapsedMilliseconds() : 0;
            Question question = sessionQuestions.get(attempt.getQuestionId());
            if (Boolean.TRUE.equals(attempt.getCorrect())) {
                correctCount++;
                nota += dataprevWeight(question);
            } else if (Boolean.FALSE.equals(attempt.getCorrect())) {
                wrongCount++;
                if (isCertoErrado(question)) {
                    nota -= dataprevWeight(question);
                }
            }
        }

        // Questao anulada conta ponto para todos, mesmo sem resposta.
        Map<Long, Attempt> answered = attempts.stream()
                .collect(Collectors.toMap(Attempt::getQuestionId, a -> a, (a, b) -> a));
        for (Question q : sessionQuestions.values()) {
            if (q.isAnnulled() && !answered.containsKey(q.getId())) {
                correctCount++;
            }
        }

        if (session.getScoringProfileId() != null) {
            applyScoringProfile(session, sessionQuestions, answered);
        }

        int total = session.questionIds().size();
        session.setCorrectCount(correctCount);
        session.setWrongCount(wrongCount);
        session.setPercent(total == 0 ? 0.0 : (correctCount * 100.0) / total);
        // Tempo da sessao = cronometro (pausas fora); sessoes antigas, sem cronometro, somam o tempo das questoes.
        session.setTotalElapsedMillis(session.getActiveMillis() != null && session.getActiveMillis() > 0
                ? session.getActiveMillis() : totalElapsed);
        // Nota Dataprev so no simulado Dataprev (tela Simulado); sessao de bloco usa o perfil de pontuacao.
        session.setNotaDataprev(session.getMode() == SessionMode.SIMULADO && session.getBlockId() == null
                && session.getScoringProfileId() == null ? nota : null);
    }

    private void applyScoringProfile(StudySession session, Map<Long, Question> questions,
                                     Map<Long, Attempt> answered) {
        var spec = profileService.spec(session.getScoringProfileId()).orElse(null);
        if (spec == null) {
            return;
        }
        List<ScoringCalculator.Item> items = new ArrayList<>();
        for (Long id : session.questionIds()) {
            Question q = questions.get(id);
            if (q == null) {
                continue;
            }
            Attempt attempt = answered.get(id);
            items.add(new ScoringCalculator.Item(q.getSubject(), attempt == null ? null : attempt.getCorrect(),
                    q.isAnnulled()));
        }
        ScoringCalculator.ScoreResult result = ScoringCalculator.calculate(spec, items);
        session.setScoreTotal(result.total());
        try {
            session.setScoreReport(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            session.setScoreReport(null);
        }
    }

    public List<ReviewItem> review(Long sessionId) {
        StudySession session = getSession(sessionId);
        List<Long> ids = session.questionIds();
        Map<Long, Attempt> byQuestion = attemptRepository.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(Attempt::getQuestionId, a -> a, (a, b) -> a));

        Map<Long, Question> questions = questionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        List<ReviewItem> items = new ArrayList<>();
        int index = 1;
        for (Long id : ids) {
            Question question = questions.get(id);
            Attempt attempt = byQuestion.get(id);
            items.add(new ReviewItem(index++, question,
                    attempt != null ? attempt.getSelectedAnswer() : null,
                    attempt != null ? attempt.getCorrect() : null,
                    attempt != null ? attempt.getGuessed() : null));
        }
        return items;
    }

    /** Na Cebraspe (Certo/Errado) cada item errado anula um certo. */
    private static boolean isCertoErrado(Question q) {
        return q != null && q.getAlternativeA() == null && q.getAlternativeB() == null
                && q.getAlternativeC() != null && q.getAlternativeE() != null;
    }

    private static double dataprevWeight(Question question) {
        if (question == null || question.getCategory() == null) {
            return 0;
        }
        return question.getCategory() == QuestionCategory.GERAL ? 1.0 : 2.5;
    }
}
