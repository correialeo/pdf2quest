package com.pdf2questao.scoring;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Grupo de disciplinas com nota minima propria (ex.: "P1 - minimo 45 de 112"). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ScoringGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long profileId;
    private String name;
    private Double minScore;
    private int position;
}
