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

    java.util.Optional<StudyPage> findBySummaryDate(java.time.LocalDate date);

    /** Resumo do dia anterior/seguinte que ja existe (as setas do resumo navegam so entre resumos escritos). */
    java.util.Optional<StudyPage> findFirstBySummaryDateLessThanOrderBySummaryDateDesc(java.time.LocalDate date);

    java.util.Optional<StudyPage> findFirstBySummaryDateGreaterThanOrderBySummaryDateAsc(java.time.LocalDate date);

    List<StudyPage> findBySummaryDateBetween(java.time.LocalDate from, java.time.LocalDate to);

    /** Pagina-mae dos resumos do dia: a raiz que ja tem algum resumo do dia dentro. */
    @Query("SELECT DISTINCT p.parentId FROM StudyPage p WHERE p.summaryDate IS NOT NULL AND p.parentId IS NOT NULL")
    List<Long> findSummaryParents();

    @Query("SELECT COALESCE(MAX(p.position), 0) FROM StudyPage p WHERE "
            + "(:parentId IS NULL AND p.parentId IS NULL) OR p.parentId = :parentId")
    int maxPosition(Long parentId);

    @Query("SELECT DISTINCT p.subject FROM StudyPage p WHERE p.subject IS NOT NULL ORDER BY p.subject")
    List<String> findDistinctSubjects();
}
