package com.pdf2questao.session;

import com.pdf2questao.question.Question;
import com.pdf2questao.question.QuestionCategory;
import com.pdf2questao.question.QuestionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StudySessionService {

    private final StudySessionRepository sessionRepository;
    private final AttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;

    public StudySessionService(StudySessionRepository sessionRepository, AttemptRepository attemptRepository,
                                QuestionRepository questionRepository) {
        this.sessionRepository = sessionRepository;
        this.attemptRepository = attemptRepository;
        this.questionRepository = questionRepository;
    }

    public StudySession startPractice(String subject, String topic, Integer year, String organization,
                                       int quantity, boolean keepExamOrder) {
        List<Long> ids = keepExamOrder
                ? questionRepository.findOrderedIds(subject, topic, year, organization, quantity)
                : questionRepository.findRandomIds(subject, topic, year, organization, quantity);
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

    public StudySession getSession(Long id) {
        return sessionRepository.findById(id).orElseThrow();
    }

    public void answer(Long sessionId, Long questionId, String selectedAnswer, Long elapsedMillis) {
        Question question = questionRepository.findById(questionId).orElseThrow();
        Attempt attempt = attemptRepository.findBySessionIdAndQuestionId(sessionId, questionId)
                .orElseGet(Attempt::new);
        attempt.setSessionId(sessionId);
        attempt.setQuestionId(questionId);
        attempt.setSelectedAnswer(selectedAnswer);
        attempt.setAnsweredAt(LocalDateTime.now());
        attempt.setElapsedMilliseconds(elapsedMillis);
        Boolean correct = question.getCorrectAnswer() == null ? null
                : question.getCorrectAnswer().equalsIgnoreCase(selectedAnswer);
        attempt.setCorrect(correct);
        attemptRepository.save(attempt);
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
            }
        }

        int total = session.questionIds().size();
        session.setCorrectCount(correctCount);
        session.setWrongCount(wrongCount);
        session.setPercent(total == 0 ? 0.0 : (correctCount * 100.0) / total);
        session.setTotalElapsedMillis(totalElapsed);
        session.setNotaDataprev(session.getMode() == SessionMode.SIMULADO ? nota : null);
        session.setFinishedAt(LocalDateTime.now());
        return sessionRepository.save(session);
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
                    attempt != null ? attempt.getCorrect() : null));
        }
        return items;
    }

    private double dataprevWeight(Long questionId) {
        Question question = questionRepository.findById(questionId).orElse(null);
        if (question == null || question.getCategory() == null) {
            return 0;
        }
        return question.getCategory() == QuestionCategory.GERAL ? 1.0 : 2.5;
    }
}
