package com.concursos.study.dashboard;

import com.concursos.study.question.QuestionRepository;
import com.concursos.study.session.AttemptRepository;
import com.concursos.study.session.SessionMode;
import com.concursos.study.session.StudySession;
import com.concursos.study.session.StudySessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DashboardService {

    private final QuestionRepository questionRepository;
    private final AttemptRepository attemptRepository;
    private final StudySessionRepository sessionRepository;

    public DashboardService(QuestionRepository questionRepository, AttemptRepository attemptRepository,
                             StudySessionRepository sessionRepository) {
        this.questionRepository = questionRepository;
        this.attemptRepository = attemptRepository;
        this.sessionRepository = sessionRepository;
    }

    public DashboardData load() {
        long totalQuestions = questionRepository.count();
        long resolved = attemptRepository.countDistinctQuestionsAnswered();
        long pending = totalQuestions - resolved;
        long correct = attemptRepository.countByCorrect(true);
        long wrong = attemptRepository.countByCorrect(false);
        double percentGeral = (correct + wrong) == 0 ? 0 : (correct * 100.0) / (correct + wrong);

        List<SubjectStat> bySubject = mapStats(attemptRepository.statsBySubject());
        List<SubjectStat> byTopic = mapStats(attemptRepository.statsByTopic());

        List<StudySession> history = sessionRepository.findByFinishedAtIsNotNullOrderByStartedAtDesc();
        Double latestNotaDataprev = history.stream()
                .filter(s -> s.getMode() == SessionMode.SIMULADO && s.getNotaDataprev() != null)
                .map(StudySession::getNotaDataprev)
                .findFirst()
                .orElse(null);

        return new DashboardData(totalQuestions, resolved, pending, correct, wrong, percentGeral,
                bySubject, byTopic, history, latestNotaDataprev);
    }

    private List<SubjectStat> mapStats(List<Object[]> rows) {
        return rows.stream()
                .map(r -> new SubjectStat((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                .toList();
    }
}
