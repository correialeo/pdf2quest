package com.pdf2questao.simulado.review;

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

/**
 * Resultado das revisoes de uma questao gerada. Cada revisao guarda o hash do conteudo que avaliou: se a questao
 * mudar depois, aquela revisao deixa de valer (volta a GERADA ate revisar de novo). Listas ficam em JSON.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class QuestionReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long questionId;
    private String contentHash;

    @Enumerated(EnumType.STRING)
    private ReviewStatus fidelityStatus;
    private Double fidelityScore;
    @Lob
    private String fidelityCriteria;
    @Lob
    private String fidelityReferences;
    @Lob
    private String fidelityIssues;
    private String fidelityHash;
    private LocalDateTime fidelityAt;

    @Enumerated(EnumType.STRING)
    private ReviewStatus materialStatus;
    @Enumerated(EnumType.STRING)
    private ContentType contentType;
    private String independentAnswer;
    private String defensibleAnswers;
    @Lob
    private String calculation;
    @Lob
    private String sources;
    private String temporalReference;
    private Boolean historicalRule;
    @Lob
    private String materialNotes;
    @Lob
    private String materialIssues;
    private String materialHash;
    private LocalDateTime materialAt;

    private String editalTopic;
    private LocalDateTime updatedAt;
}
