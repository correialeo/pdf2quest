package com.pdf2questao.simulado;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedExamRepository extends JpaRepository<GeneratedExam, Long> {

    List<GeneratedExam> findByBlockIdIsNullOrderByCreatedAtDesc();
}
