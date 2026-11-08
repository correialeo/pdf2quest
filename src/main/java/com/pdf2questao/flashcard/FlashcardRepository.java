package com.pdf2questao.flashcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FlashcardRepository extends JpaRepository<Flashcard, Long> {

    List<Flashcard> findByLessonIdOrderById(Long lessonId);

    List<Flashcard> findByLessonIdInOrderById(Collection<Long> lessonIds);
}
