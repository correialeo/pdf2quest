package com.pdf2questao.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(nullable = false)
    private String statement;

    @Lob
    private String alternativeA;
    @Lob
    private String alternativeB;
    @Lob
    private String alternativeC;
    @Lob
    private String alternativeD;
    @Lob
    private String alternativeE;

    private String correctAnswer;

    /** Numero da questao na prova original (para manter/reproduzir a ordem do PDF). */
    private Integer questionNumber;

    /** ImportJob que originou esta questao (usado para linkar o gabarito correto). */
    private Long importJobId;

    /** Pagina do PDF original onde a questao comeca. */
    private Integer page;

    private Long passageId;

    @Lob
    private String explanation;

    private String subject;
    private String topic;
    private String organization;
    private String exam;
    private Integer year;
    private String difficulty;
    private String source;

    @Enumerated(EnumType.STRING)
    private QuestionCategory category;

    private LocalDateTime createdAt = LocalDateTime.now();
}
