package com.pdf2questao.aula;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Resposta a uma pergunta de quiz (refazer o quiz grava outra; a ultima e a que vale no desempenho). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long quizQuestionId;
    private String selectedAnswer;
    private Boolean correct;
    private LocalDateTime answeredAt = LocalDateTime.now();
}
