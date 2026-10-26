package com.pdf2questao.session;

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
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Concurso (workspace) a que pertence; nulo = sem concurso. */
    private Long concursoId;

    @Enumerated(EnumType.STRING)
    private SessionMode mode;

    @Lob
    private String questionIdsOrder;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private int correctCount;
    private int wrongCount;
    private Double percent;
    private Double notaDataprev;
    private Long totalElapsedMillis;

    /** Bloco de estudo de onde a sessao saiu (null para sessoes de prova inteira/pratica). */
    private Long blockId;
    private String blockName;
    private Integer timeLimitMinutes;
    private Long scoringProfileId;
    private Double scoreTotal;

    /** Cronometro: tempo de resolucao com o resolver aberto e sem pausa (null = sessao anterior ao recurso). */
    private Long activeMillis;

    /** Resultado por disciplina/grupo em JSON, congelado no fim da sessao. */
    @Lob
    private String scoreReport;

    /** Tempo de resolucao contado ate agora (pausas e tempo fora do resolver nao contam). */
    public long activeMillisOrZero() {
        return activeMillis == null ? 0 : activeMillis;
    }

    public java.util.List<Long> questionIds() {
        if (questionIdsOrder == null || questionIdsOrder.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(questionIdsOrder.split(","))
                .map(Long::parseLong)
                .toList();
    }
}
