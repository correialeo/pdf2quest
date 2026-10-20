package com.pdf2questao.simulado.review;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Versao anterior de uma questao corrigida, com o estado das revisoes naquele momento. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class QuestionRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long questionId;
    @Lob
    private String statement;
    @Lob
    private String alternatives;
    private String correctAnswer;
    @Lob
    private String explanation;
    @Lob
    private String reviewSnapshot;
    private String reason;
    private LocalDateTime createdAt = LocalDateTime.now();
}
