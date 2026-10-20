package com.pdf2questao.simulado.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuestionRevisionRepository extends JpaRepository<QuestionRevision, Long> {

    List<QuestionRevision> findByQuestionIdInOrderByCreatedAt(Collection<Long> questionIds);
}
