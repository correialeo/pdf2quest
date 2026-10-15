package com.pdf2questao.study;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StudyPageRepository extends JpaRepository<StudyPage, Long> {

    List<StudyPage> findByParentId(Long parentId);

    List<StudyPage> findTop6ByOrderByUpdatedAtDesc();

    @Query("SELECT COALESCE(MAX(p.position), 0) FROM StudyPage p WHERE "
            + "(:parentId IS NULL AND p.parentId IS NULL) OR p.parentId = :parentId")
    int maxPosition(Long parentId);

    @Query("SELECT DISTINCT p.subject FROM StudyPage p WHERE p.subject IS NOT NULL ORDER BY p.subject")
    List<String> findDistinctSubjects();
}
