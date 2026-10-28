package com.pdf2questao.study;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StudyPageRepository extends JpaRepository<StudyPage, Long> {

    List<StudyPage> findByParentId(Long parentId);

    List<StudyPage> findTop50ByOrderByUpdatedAtDesc();

    /** Paginas com o termo no titulo ou no texto (sem diferenciar maiusculas), as editadas por ultimo primeiro. */
    @Query(value = "SELECT * FROM study_page WHERE title LIKE '%' || :term || '%' "
            + "OR content LIKE '%' || :term || '%' ORDER BY updated_at DESC", nativeQuery = true)
    List<StudyPage> search(@org.springframework.data.repository.query.Param("term") String term);

    /** Resumo do dia de um concurso (nulo = o resumo geral, sem concurso). */
    @Query("SELECT p FROM StudyPage p WHERE p.summaryDate = :date AND (p.concursoId = :concursoId "
            + "OR (:concursoId IS NULL AND p.concursoId IS NULL))")
    java.util.Optional<StudyPage> findSummary(java.time.LocalDate date, Long concursoId);

    /** Resumo do dia anterior/seguinte do mesmo concurso que ja existe (as setas navegam so entre resumos escritos). */
    @Query("SELECT p FROM StudyPage p WHERE p.summaryDate < :date AND (p.concursoId = :concursoId "
            + "OR (:concursoId IS NULL AND p.concursoId IS NULL)) ORDER BY p.summaryDate DESC")
    List<StudyPage> summariesBefore(java.time.LocalDate date, Long concursoId,
                                    org.springframework.data.domain.Pageable page);

    @Query("SELECT p FROM StudyPage p WHERE p.summaryDate > :date AND (p.concursoId = :concursoId "
            + "OR (:concursoId IS NULL AND p.concursoId IS NULL)) ORDER BY p.summaryDate ASC")
    List<StudyPage> summariesAfter(java.time.LocalDate date, Long concursoId,
                                   org.springframework.data.domain.Pageable page);

    @Query("SELECT p FROM StudyPage p WHERE p.summaryDate BETWEEN :from AND :to AND (p.concursoId = :concursoId "
            + "OR (:concursoId IS NULL AND p.concursoId IS NULL))")
    List<StudyPage> summariesBetween(java.time.LocalDate from, java.time.LocalDate to, Long concursoId);

    /** Pagina-mae dos resumos do dia de um concurso: a raiz que ja tem algum resumo dele dentro. */
    @Query("SELECT DISTINCT p.parentId FROM StudyPage p WHERE p.summaryDate IS NOT NULL AND p.parentId IS NOT NULL "
            + "AND (p.concursoId = :concursoId OR (:concursoId IS NULL AND p.concursoId IS NULL))")
    List<Long> findSummaryParents(Long concursoId);

    @Query("SELECT COALESCE(MAX(p.position), 0) FROM StudyPage p WHERE "
            + "(:parentId IS NULL AND p.parentId IS NULL) OR p.parentId = :parentId")
    int maxPosition(Long parentId);

    @Query("SELECT DISTINCT p.subject FROM StudyPage p WHERE p.subject IS NOT NULL ORDER BY p.subject")
    List<String> findDistinctSubjects();
}
