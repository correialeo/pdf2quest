package com.pdf2questao.discursiva;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Prova discursiva: enunciado e criterios (do Claude), o texto do usuario e a correcao. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Essay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    @Lob
    private String prompt;
    private String banca;
    /** Criterios de correcao, um por linha (ex.: "Conteúdo – 60 pontos"). */
    @Lob
    private String criteria;
    private Integer timeLimitMinutes;
    private Integer maxLines;
    private Double maxScore;

    @Lob
    private String text;
    /** Tempo escrevendo (com a pagina aberta e sem pausa), contado pelo navegador. */
    private Long activeMillis;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;

    private Double score;
    /** Correcao em Markdown simples (convertida para HTML ao exibir). */
    @Lob
    private String feedback;
    private LocalDateTime correctedAt;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt;

    public boolean isSubmitted() {
        return submittedAt != null;
    }

    public boolean isCorrected() {
        return correctedAt != null;
    }
}
