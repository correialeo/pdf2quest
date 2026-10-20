package com.pdf2questao.aula;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    List<QuizAttempt> findByQuizQuestionIdInOrderByAnsweredAt(Collection<Long> questionIds);

    /**
     * [disciplina, topico, acertos, respondidas, ultima resposta] considerando so a ultima resposta de cada pergunta.
     */
    @Query(value = "SELECT COALESCE(q.subject, '(sem disciplina)'), COALESCE(q.topic, '(sem tópico)'), "
            + "SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*), MAX(a.answered_at) "
            + "FROM quiz_attempt a JOIN quiz_question q ON q.id = a.quiz_question_id "
            + "WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM quiz_attempt a2 "
            + "  WHERE a2.quiz_question_id = a.quiz_question_id) "
            + "GROUP BY 1, 2 ORDER BY 3 * 1.0 / COUNT(*), 1, 2", nativeQuery = true)
    List<Object[]> statsByTopic();
}
