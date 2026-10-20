package com.pdf2questao.simulado.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuestionReviewRepository extends JpaRepository<QuestionReview, Long> {

    Optional<QuestionReview> findByQuestionId(Long questionId);

    List<QuestionReview> findByQuestionIdIn(Collection<Long> questionIds);
}
