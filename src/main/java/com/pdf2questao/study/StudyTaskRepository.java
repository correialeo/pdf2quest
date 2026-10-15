package com.pdf2questao.study;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface StudyTaskRepository extends JpaRepository<StudyTask, Long> {

    List<StudyTask> findByPageId(Long pageId);

    List<StudyTask> findByPlannedDate(java.time.LocalDate date);

    @Modifying
    @Query("UPDATE StudyTask t SET t.pageId = NULL WHERE t.pageId IN :pageIds")
    int unlinkPages(Collection<Long> pageIds);

    @Query("SELECT DISTINCT t.subject FROM StudyTask t WHERE t.subject IS NOT NULL ORDER BY t.subject")
    List<String> findDistinctSubjects();
}
