package com.pdf2questao.concurso;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Concurso que o usuario acompanha. Funciona como workspace: cronogramas, aulas, simulados e metricas ficam ligados
 * a ele. O edital e a pontuacao vem do {@link #scoringProfileId} (perfil de pontuacao).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Concurso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    private String orgao;
    private String banca;
    private String cargo;

    private LocalDate dataProva;
    private LocalDate dataInscricaoFim;
    private String linkEdital;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConcursoStatus status = ConcursoStatus.ESTUDANDO;

    private Long scoringProfileId;

    /** Cor do concurso na barra lateral (hex, ex.: "#0d6efd"). */
    private String cor;

    @Lob
    private String notas;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime arquivadoEm;

    public boolean isArquivado() {
        return arquivadoEm != null;
    }

    /** Dias ate a prova (negativo = ja passou); nulo sem data. */
    public Long diasParaProva(LocalDate today) {
        return dataProva == null ? null : ChronoUnit.DAYS.between(today, dataProva);
    }

    public String getCorOuPadrao() {
        return cor == null || cor.isBlank() ? "#6c757d" : cor;
    }
}
