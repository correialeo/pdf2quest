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

    /** Leitura incerta (OCR, imagem, tabela, codigo): conferir no PDF original. */
    private boolean needsReview;

    @Column(length = 500)
    private String reviewReason;

    /** Questao anulada pela banca: conta ponto para todos e nao tem letra correta. */
    private boolean annulled;

    @Lob
    private String explanation;

    @Lob
    private String note;

    private String subject;
    private String topic;
    private String organization;
    private String exam;
    private Integer year;
    private String difficulty;
    private String source;

    @Enumerated(EnumType.STRING)
    private QuestionCategory category;

    /** Identificador estavel vindo de um arquivo JSON/CSV; nulo nas questoes lidas de PDF. */
    private String externalId;

    /** Tipo informado na importacao estruturada; nulo nas questoes de PDF (o tipo sai das alternativas). */
    @Enumerated(EnumType.STRING)
    private QuestionType questionType;

    /** Subtopicos do assunto, um por linha. */
    @Lob
    private String subtopics;

    /** Tags separadas por virgula. */
    @Lob
    private String tags;

    /** Referencias e metadados livres do arquivo importado (JSON). */
    @Lob
    private String extraMetadata;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Alternativas preenchidas, na ordem A-E (letra -> texto), para as telas montarem a lista num laco so. */
    public java.util.Map<String, String> alternatives() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        String[] texts = {alternativeA, alternativeB, alternativeC, alternativeD, alternativeE};
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] != null) {
                map.put(String.valueOf((char) ('A' + i)), texts[i]);
            }
        }
        return map;
    }

    /** Item Certo/Errado (Cebraspe): alternativas A = "Certo" e B = "Errado", e nada mais. */
    public boolean certoErrado() {
        return "Certo".equals(alternativeA) && "Errado".equals(alternativeB) && alternativeC == null;
    }

    /** Letra gravada de um julgamento C/E: C, Certo ou A = A; E, Errado ou B = B; outro valor = null. */
    public static String certoErradoLetter(String answer) {
        if (answer == null) {
            return null;
        }
        return switch (answer.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "C", "CERTO", "A" -> "A";
            case "E", "ERRADO", "B" -> "B";
            default -> null;
        };
    }
}
