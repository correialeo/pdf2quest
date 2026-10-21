package com.pdf2questao.aula;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface LessonRepository extends JpaRepository<Lesson, Long> {

    List<Lesson> findAllByOrderByCreatedAtDesc();

    List<Lesson> findByTaskIdInOrderByCreatedAt(Collection<Long> taskIds);
}
