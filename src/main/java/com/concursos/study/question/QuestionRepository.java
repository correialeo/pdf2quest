package com.concursos.study.question;

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
            "(:organization IS NULL OR organization = :organization) " +
            "ORDER BY RANDOM() LIMIT :quantity", nativeQuery = true)
    List<Long> findRandomIds(@Param("subject") String subject,
                              @Param("topic") String topic,
                              @Param("year") Integer year,
                              @Param("organization") String organization,
                              @Param("quantity") int quantity);

    @Query(value = "SELECT id FROM question WHERE " +
            "(:subject IS NULL OR subject = :subject) AND " +
            "(:topic IS NULL OR topic = :topic) AND " +
            "(:year IS NULL OR year = :year) AND " +
            "(:organization IS NULL OR organization = :organization) " +
            "ORDER BY question_number ASC, id ASC LIMIT :quantity", nativeQuery = true)
    List<Long> findOrderedIds(@Param("subject") String subject,
                               @Param("topic") String topic,
                               @Param("year") Integer year,
                               @Param("organization") String organization,
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

    long countBySubject(String subject);
}
