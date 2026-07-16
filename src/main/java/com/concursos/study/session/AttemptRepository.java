package com.concursos.study.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    Optional<Attempt> findBySessionIdAndQuestionId(Long sessionId, Long questionId);

    List<Attempt> findBySessionId(Long sessionId);

    long countByCorrect(Boolean correct);

    @Query(value = "SELECT COUNT(DISTINCT question_id) FROM attempt", nativeQuery = true)
    long countDistinctQuestionsAnswered();

    @Query(value = "SELECT q.subject, SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE a.correct IS NOT NULL AND q.subject IS NOT NULL " +
            "GROUP BY q.subject ORDER BY q.subject", nativeQuery = true)
    List<Object[]> statsBySubject();

    @Query(value = "SELECT q.topic, SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE a.correct IS NOT NULL AND q.topic IS NOT NULL " +
            "GROUP BY q.topic ORDER BY q.topic", nativeQuery = true)
    List<Object[]> statsByTopic();
}
