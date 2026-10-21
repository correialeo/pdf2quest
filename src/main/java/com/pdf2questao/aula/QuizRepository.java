package com.pdf2questao.aula;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    List<Quiz> findByLessonIdOrderByCreatedAt(Long lessonId);

    List<Quiz> findAllByOrderByCreatedAtDesc();

    List<Quiz> findByTaskIdInOrderByCreatedAt(Collection<Long> taskIds);
}
