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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StudySessionService(StudySessionRepository sessionRepository, AttemptRepository attemptRepository,
                                QuestionRepository questionRepository, BlockService blockService,
                                ScoringProfileService profileService) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
        this.blockService = blockService;
        this.profileService = profileService;
    }

    /** Inicia uma sessao com as questoes do bloco (origens, faixas e exclusoes ja aplicadas). */
    public StudySession startBloco(Long blockId) {
        StudyBlock block = blockService.find(blockId).orElseThrow();
        List<Long> ids = blockService.questionIds(blockId);
        if (ids.isEmpty()) {
            throw new IllegalStateException("O bloco '" + block.getName() + "' nao tem questoes.");
        }
        StudySession session = createSession(SessionMode.SIMULADO, ids);
        session.setBlockId(block.getId());
        session.setBlockName(block.getName());
        session.setScoringProfileId(block.getScoringProfileId());
        session.setTimeLimitMinutes(block.getTimeLimitMinutes());
        return sessionRepository.save(session);
    }

    /** Sessao do bloco que foi iniciada e ainda nao terminou (a mais recente), se houver. */
    public java.util.Optional<StudySession> openBlocoSession(Long blockId) {
        return sessionRepository.findFirstByBlockIdAndFinishedAtIsNullOrderByStartedAtDesc(blockId);
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

    /** Resultado do perfil de pontuacao congelado no fim da sessao (null se a sessao nao usa perfil). */
    public ScoringCalculator.ScoreResult scoreResult(StudySession session) {
        if (session.getScoreReport() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(session.getScoreReport(), ScoringCalculator.ScoreResult.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public StudySession startPractice(String subject, String topic, Integer year, String organization,
                                       Long importJobId, int quantity, boolean keepExamOrder) {
        List<Long> ids = keepExamOrder
                ? questionRepository.findOrderedIds(subject, topic, year, organization, importJobId, quantity)
                : questionRepository.findRandomIds(subject, topic, year, organization, importJobId, quantity);
        return createSession(SessionMode.PRATICA, ids);
    }

    public StudySession startSimulado(int qtyGerais, int qtyEspecificas, boolean keepExamOrder) {
        List<Long> ids = new ArrayList<>();
        if (keepExamOrder) {
            ids.addAll(questionRepository.findOrderedIdsByCategory(QuestionCategory.GERAL.name(), qtyGerais));
            ids.addAll(questionRepository.findOrderedIdsByCategory(QuestionCategory.ESPECIFICO.name(), qtyEspecificas));
        } else {
            ids.addAll(questionRepository.findRandomIdsByCategory(QuestionCategory.GERAL.name(), qtyGerais));
            ids.addAll(questionRepository.findRandomIdsByCategory(QuestionCategory.ESPECIFICO.name(), qtyEspecificas));
            Collections.shuffle(ids);
        }
        return createSession(SessionMode.SIMULADO, ids);
    }

    public StudySession startProva(Long importJobId) {
        List<Long> ids = questionRepository.findByImportJobId(importJobId).stream()
                .sorted(Comparator.comparing(Question::getQuestionNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Question::getId)
                .toList();
        return createSession(SessionMode.SIMULADO, ids);
    }

    public StudySession startRefazer(List<Long> questionIds) {
        return createSession(SessionMode.REFAZER, questionIds);
    }

    private StudySession createSession(SessionMode mode, List<Long> ids) {
        StudySession session = new StudySession();
        session.setMode(mode);
        session.setQuestionIdsOrder(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        session.setStartedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    /** Resposta ja dada nesta sessao a uma questao (para repor a alternativa marcada e o "E chute?"). */
    public java.util.Optional<Attempt> attemptOf(Long sessionId, Long questionId) {
        return attemptRepository.findBySessionIdAndQuestionId(sessionId, questionId);
    }

    public StudySession getSession(Long id) {
        return sessionRepository.findById(id).orElseThrow();
    }

    public void answer(Long sessionId, Long questionId, String selectedAnswer, Long elapsedMillis) {
        answer(sessionId, questionId, selectedAnswer, elapsedMillis, null);
    }

    /** @param guess "E chute?"; null mantem o que ja estava marcado nesta resposta (ou nao informado). */
    public void answer(Long sessionId, Long questionId, String selectedAnswer, Long elapsedMillis, Boolean guess) {
        Question question = questionRepository.findById(questionId).orElseThrow();
        Attempt attempt = attemptRepository.findBySessionIdAndQuestionId(sessionId, questionId)
                .orElseGet(Attempt::new);
        attempt.setSessionId(sessionId);
        attempt.setQuestionId(questionId);
        attempt.setSelectedAnswer(selectedAnswer);
        attempt.setAnsweredAt(LocalDateTime.now());
        attempt.setElapsedMilliseconds(elapsedMillis);
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

    public StudySession finish(Long sessionId) {
        StudySession session = getSession(sessionId);
        List<Attempt> attempts = attemptRepository.findBySessionId(sessionId);

        int correctCount = 0;
        int wrongCount = 0;
        long totalElapsed = 0;
        double nota = 0;

        for (Attempt attempt : attempts) {
            totalElapsed += attempt.getElapsedMilliseconds() != null ? attempt.getElapsedMilliseconds() : 0;
            if (Boolean.TRUE.equals(attempt.getCorrect())) {
                correctCount++;
                if (session.getMode() == SessionMode.SIMULADO) {
                    nota += dataprevWeight(attempt.getQuestionId());
                }
            } else if (Boolean.FALSE.equals(attempt.getCorrect())) {
                wrongCount++;
                if (session.getMode() == SessionMode.SIMULADO && isCertoErrado(attempt.getQuestionId())) {
                    nota -= dataprevWeight(attempt.getQuestionId());
                }
            }
        }

        // Questao anulada conta ponto para todos, mesmo sem resposta.
        Map<Long, Attempt> answered = attempts.stream()
                .collect(Collectors.toMap(Attempt::getQuestionId, a -> a, (a, b) -> a));
        Map<Long, Question> sessionQuestions = questionRepository.findAllById(session.questionIds()).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
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
        session.setTotalElapsedMillis(totalElapsed);
        // Sessao com perfil de pontuacao usa a nota do perfil; as demais mantem a nota Dataprev de sempre.
        session.setNotaDataprev(session.getMode() == SessionMode.SIMULADO && session.getScoringProfileId() == null
                ? nota : null);
        session.setFinishedAt(LocalDateTime.now());
        return sessionRepository.save(session);
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

        List<ReviewItem> items = new ArrayList<>();
        int index = 1;
        for (Long id : ids) {
            Question question = questionRepository.findById(id).orElse(null);
            Attempt attempt = byQuestion.get(id);
            items.add(new ReviewItem(index++, question,
                    attempt != null ? attempt.getSelectedAnswer() : null,
                    attempt != null ? attempt.getCorrect() : null,
                    attempt != null ? attempt.getGuessed() : null));
        }
        return items;
    }

    /** Na Cebraspe (Certo/Errado) cada item errado anula um certo. */
    private boolean isCertoErrado(Long questionId) {
        return questionRepository.findById(questionId)
                .map(q -> q.getAlternativeA() == null && q.getAlternativeB() == null
                        && q.getAlternativeC() != null && q.getAlternativeE() != null)
                .orElse(false);
    }

    private double dataprevWeight(Long questionId) {
        Question question = questionRepository.findById(questionId).orElse(null);
        if (question == null || question.getCategory() == null) {
            return 0;
        }
        return question.getCategory() == QuestionCategory.GERAL ? 1.0 : 2.5;
    }
}
