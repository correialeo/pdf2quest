package com.pdf2questao.aula;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceExcerptRepository extends JpaRepository<SourceExcerpt, Long> {

    List<SourceExcerpt> findByLessonIdOrderById(Long lessonId);

    List<SourceExcerpt> findBySha256(String sha256);
}
