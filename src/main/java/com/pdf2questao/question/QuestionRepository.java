package com.pdf2questao.question;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    @Query("SELECT DISTINCT q.subject FROM Question q WHERE q.subject IS NOT NULL ORDER BY q.subject")
    List<String> findDistinctSubjects();

    @Query("SELECT DISTINCT q.topic FROM Question q WHERE q.topic IS NOT NULL ORDER BY q.topic")
    List<String> findDistinctTopics();

    @Query("SELECT DISTINCT q.organization FROM Question q WHERE q.organization IS NOT NULL ORDER BY q.organization")
    List<String> findDistinctOrganizations();

    @Query("SELECT DISTINCT q.year FROM Question q WHERE q.year IS NOT NULL ORDER BY q.year DESC")
    List<Integer> findDistinctYears();

    @Query(value = "SELECT id FROM question WHERE " +
            "(:subject IS NULL OR subject = :subject) AND " +
            "(:topic IS NULL OR topic = :topic) AND " +
            "(:year IS NULL OR year = :year) AND " +
            "(:organization IS NULL OR organization = :organization) AND " +
            "(:jobId IS NULL OR import_job_id = :jobId) " +
            "ORDER BY RANDOM() LIMIT :quantity", nativeQuery = true)
    List<Long> findRandomIds(@Param("subject") String subject,
                              @Param("topic") String topic,
                              @Param("year") Integer year,
                              @Param("organization") String organization,
                              @Param("jobId") Long jobId,
                              @Param("quantity") int quantity);

    @Query(value = "SELECT id FROM question WHERE " +
            "(:subject IS NULL OR subject = :subject) AND " +
            "(:topic IS NULL OR topic = :topic) AND " +
            "(:year IS NULL OR year = :year) AND " +
            "(:organization IS NULL OR organization = :organization) AND " +
            "(:jobId IS NULL OR import_job_id = :jobId) " +
            "ORDER BY question_number ASC, id ASC LIMIT :quantity", nativeQuery = true)
    List<Long> findOrderedIds(@Param("subject") String subject,
                               @Param("topic") String topic,
                               @Param("year") Integer year,
                               @Param("organization") String organization,
                               @Param("jobId") Long jobId,
                               @Param("quantity") int quantity);

    @Query(value = "SELECT id FROM question WHERE category = :category ORDER BY RANDOM() LIMIT :quantity",
            nativeQuery = true)
    List<Long> findRandomIdsByCategory(@Param("category") String category, @Param("quantity") int quantity);

    @Query(value = "SELECT id FROM question WHERE category = :category " +
            "ORDER BY question_number ASC, id ASC LIMIT :quantity", nativeQuery = true)
    List<Long> findOrderedIdsByCategory(@Param("category") String category, @Param("quantity") int quantity);

    @Query(value = "SELECT q.* FROM question q WHERE q.id IN (" +
            "  SELECT a.question_id FROM attempt a " +
            "  WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM attempt a2 WHERE a2.question_id = a.question_id) " +
            "  AND a.correct = 0" +
            ") ORDER BY q.id DESC", nativeQuery = true)
    List<Question> findErrorNotebook();

    /** Quantas questoes estao no caderno de erros (a ultima resposta foi errada), sem carregar as questoes. */
    @Query(value = "SELECT COUNT(DISTINCT a.question_id) FROM attempt a " +
            "WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM attempt a2 WHERE a2.question_id = a.question_id) " +
            "AND a.correct = 0", nativeQuery = true)
    long countErrorNotebook();

    /** [import_job_id, quantidade de questoes] de cada prova. */
    @Query(value = "SELECT import_job_id, COUNT(*) FROM question WHERE import_job_id IS NOT NULL " +
            "GROUP BY import_job_id", nativeQuery = true)
    List<Object[]> countsByJob();

    @Query(value = "SELECT q.* FROM question q WHERE q.import_job_id = :jobId AND q.id IN (" +
            "  SELECT a.question_id FROM attempt a " +
            "  WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM attempt a2 WHERE a2.question_id = a.question_id) " +
            "  AND a.correct = 0" +
            ") ORDER BY q.question_number, q.id", nativeQuery = true)
    List<Question> findErrorNotebookByJob(@Param("jobId") Long jobId);

    long countBySubject(String subject);

    List<Question> findByImportJobId(Long importJobId);

    /**
     * Questoes reais da banca (com gabarito, sem as escritas pelo Claude), sorteadas, para servir de modelo de estilo.
     * {@code subject} filtra por parte do nome da disciplina (vazio = todas).
     */
    @Query(value = "SELECT * FROM question WHERE upper(organization) = upper(:banca) AND correct_answer IS NOT NULL " +
            "AND (source IS NULL OR source NOT IN ('Simulado do Claude', 'Treino no Claude')) " +
            "AND (:subject IS NULL OR lower(subject) LIKE '%' || lower(:subject) || '%') " +
            "ORDER BY random() LIMIT :quantity", nativeQuery = true)
    List<Question> findBancaSamples(@Param("banca") String banca, @Param("subject") String subject,
                                    @Param("quantity") int quantity);

    /** [import_job_id, questoes] das provas reais da banca no banco (sem as escritas pelo Claude). */
    @Query(value = "SELECT import_job_id, COUNT(*) FROM question WHERE upper(organization) = upper(:banca) " +
            "AND import_job_id IS NOT NULL AND (source IS NULL OR source NOT IN ('Simulado do Claude', 'Treino no Claude')) " +
            "GROUP BY import_job_id", nativeQuery = true)
    List<Object[]> bancaJobs(@Param("banca") String banca);

    /** [disciplina, erros no caderno] (a ultima resposta da questao foi errada). */
    @Query(value = "SELECT COALESCE(q.subject, '(sem disciplina)'), COUNT(*) FROM question q WHERE q.id IN (" +
            "  SELECT a.question_id FROM attempt a " +
            "  WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM attempt a2 WHERE a2.question_id = a.question_id) " +
            "  AND a.correct = 0) GROUP BY 1 ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> errorNotebookBySubject();

    /** [disciplina, acertos no chute] (a ultima resposta acertou, marcada como chute). */
    @Query(value = "SELECT COALESCE(q.subject, '(sem disciplina)'), COUNT(*) FROM question q WHERE q.id IN (" +
            "  SELECT a.question_id FROM attempt a " +
            "  WHERE a.answered_at = (SELECT MAX(a2.answered_at) FROM attempt a2 WHERE a2.question_id = a.question_id) " +
            "  AND a.correct = 1 AND a.guessed = 1) GROUP BY 1 ORDER BY 2 DESC", nativeQuery = true)
    List<Object[]> luckyGuessesBySubject();

    /** [id, external_id] das questoes que vieram de JSON/CSV com identificador. */
    @Query("SELECT q.id, q.externalId FROM Question q WHERE q.externalId IS NOT NULL")
    List<Object[]> findExternalIds();

    /** [id, enunciado] de todas as questoes, para apontar possiveis duplicatas na importacao. */
    @Query("SELECT q.id, q.statement FROM Question q")
    List<Object[]> findStatements();

    List<Question> findByExternalIdIn(java.util.Collection<String> externalIds);
}
