package com.pdf2questao.scoring;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Perfil de pontuacao: grupos (ex.: P1, P2) com regras de peso por disciplina e notas minimas. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ScoringProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Lob
    private String description;

    /** Nota minima total (soma de todos os grupos); null = sem minimo. */
    private Double minTotal;

    private LocalDateTime createdAt = LocalDateTime.now();
}
