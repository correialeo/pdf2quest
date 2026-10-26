package com.pdf2questao.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    Optional<Attempt> findBySessionIdAndQuestionId(Long sessionId, Long questionId);

    List<Attempt> findBySessionId(Long sessionId);

    void deleteBySessionId(Long sessionId);

    /**
     * Recorrige as respostas das questoes com o gabarito atual (anulada = acerto; sem gabarito = sem correcao).
     * Usado quando o gabarito ou as anulacoes chegam ou mudam depois de a questao ja ter sido respondida.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE attempt SET correct = (SELECT CASE WHEN q.annulled = 1 THEN 1 " +
            "WHEN q.correct_answer IS NULL OR attempt.selected_answer IS NULL THEN NULL " +
            "WHEN upper(q.correct_answer) = upper(attempt.selected_answer) THEN 1 ELSE 0 END " +
            "FROM question q WHERE q.id = attempt.question_id) WHERE question_id IN (:questionIds)",
            nativeQuery = true)
    int regrade(@Param("questionIds") java.util.Collection<Long> questionIds);

    @Query("SELECT DISTINCT a.sessionId FROM Attempt a WHERE a.questionId IN :questionIds")
    List<Long> sessionIdsWithQuestions(@Param("questionIds") java.util.Collection<Long> questionIds);

    @Query(value = "SELECT a.question_id, a.selected_answer FROM attempt a " +
            "WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM attempt a2 WHERE a2.question_id = a.question_id) " +
            "AND a.correct = 0", nativeQuery = true)
    List<Object[]> findLatestWrongAnswers();

    /** Sessoes do concurso (para filtrar respostas por workspace). */
    String IN_CONCURSO = "(:c IS NULL OR a.session_id IN (SELECT s.id FROM study_session s WHERE s.concurso_id = :c))";

    long countByCorrect(Boolean correct);

    @Query(value = "SELECT COUNT(DISTINCT question_id) FROM attempt", nativeQuery = true)
    long countDistinctQuestionsAnswered();

    @Query(value = "SELECT q.subject, SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE a.correct IS NOT NULL AND q.subject IS NOT NULL " +
            "GROUP BY q.subject ORDER BY q.subject", nativeQuery = true)
    List<Object[]> statsBySubject();

    /** Como {@link #statsBySubject()}, so com as respostas dadas em sessoes do concurso (nulo = todas). */
    @Query(value = "SELECT q.subject, SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE a.correct IS NOT NULL AND q.subject IS NOT NULL AND " + IN_CONCURSO + " " +
            "GROUP BY q.subject ORDER BY q.subject", nativeQuery = true)
    List<Object[]> statsBySubject(@Param("c") Long concursoId);

    @Query(value = "SELECT COUNT(DISTINCT a.question_id) FROM attempt a WHERE " + IN_CONCURSO, nativeQuery = true)
    long countDistinctQuestionsAnswered(@Param("c") Long concursoId);

    /** [acertos, erros] nas sessoes do concurso (nulo = todas). */
    @Query(value = "SELECT COALESCE(SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN a.correct = 0 THEN 1 ELSE 0 END), 0) FROM attempt a WHERE " + IN_CONCURSO,
            nativeQuery = true)
    List<Object[]> totals(@Param("c") Long concursoId);

    @Query(value = "SELECT q.topic, SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE a.correct IS NOT NULL AND q.topic IS NOT NULL " +
            "GROUP BY q.topic ORDER BY q.topic", nativeQuery = true)
    List<Object[]> statsByTopic();

    // ---- estatisticas por prova (import_job) ----

    /** [import_job_id, questoes distintas respondidas, acertos, erros] de todas as provas. */
    @Query(value = "SELECT q.import_job_id, COUNT(DISTINCT a.question_id), " +
            "COALESCE(SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN a.correct = 0 THEN 1 ELSE 0 END), 0) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE q.import_job_id IS NOT NULL GROUP BY q.import_job_id", nativeQuery = true)
    List<Object[]> totalsByJob();

    /** [import_job_id, session_id] das sessoes que tem resposta em questoes de cada prova. */
    @Query(value = "SELECT DISTINCT q.import_job_id, a.session_id FROM attempt a " +
            "JOIN question q ON q.id = a.question_id WHERE q.import_job_id IS NOT NULL", nativeQuery = true)
    List<Object[]> sessionsByJob();

    /** [disciplina, questoes da prova, respondidas, acertos, erros], na ordem da prova. */
    @Query(value = "SELECT COALESCE(q.subject, '(sem disciplina)'), COUNT(DISTINCT q.id), " +
            "COUNT(DISTINCT a.question_id), " +
            "COALESCE(SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN a.correct = 0 THEN 1 ELSE 0 END), 0) " +
            "FROM question q LEFT JOIN attempt a ON a.question_id = q.id " +
            "WHERE q.import_job_id = :jobId GROUP BY COALESCE(q.subject, '(sem disciplina)') " +
            "ORDER BY MIN(q.question_number)", nativeQuery = true)
    List<Object[]> subjectProgressForJob(@Param("jobId") Long jobId);

    @Query(value = "SELECT q.topic, SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), COUNT(*) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE a.correct IS NOT NULL AND q.topic IS NOT NULL AND q.import_job_id = :jobId " +
            "GROUP BY q.topic ORDER BY q.topic", nativeQuery = true)
    List<Object[]> statsByTopicForJob(@Param("jobId") Long jobId);

    /** [session_id, acertos, erros] das respostas dadas a questoes da prova, por sessao. */
    @Query(value = "SELECT a.session_id, " +
            "COALESCE(SUM(CASE WHEN a.correct = 1 THEN 1 ELSE 0 END), 0), " +
            "COALESCE(SUM(CASE WHEN a.correct = 0 THEN 1 ELSE 0 END), 0) " +
            "FROM attempt a JOIN question q ON q.id = a.question_id " +
            "WHERE q.import_job_id = :jobId GROUP BY a.session_id", nativeQuery = true)
    List<Object[]> sessionStatsForJob(@Param("jobId") Long jobId);
}
