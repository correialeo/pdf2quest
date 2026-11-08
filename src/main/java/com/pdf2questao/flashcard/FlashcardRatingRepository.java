package com.pdf2questao.flashcard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FlashcardRatingRepository extends JpaRepository<FlashcardRating, Long> {

    List<FlashcardRating> findByQuizIdOrderByRatedAt(Long quizId);

    List<FlashcardRating> findByFlashcardIdInOrderByRatedAt(Collection<Long> flashcardIds);

    void deleteByFlashcardIdIn(Collection<Long> flashcardIds);
}
