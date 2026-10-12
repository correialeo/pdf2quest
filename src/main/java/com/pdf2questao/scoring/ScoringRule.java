package com.pdf2questao.scoring;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Peso de uma disciplina dentro de um grupo. {@code subject} e o nome da disciplina e
 * {@code matchSubjects} lista outros nomes (um por linha) que contam para a mesma regra.
 * {@code questionCount} e a quantidade de questoes prevista no edital (define o maximo de pontos).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ScoringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long groupId;
    private String subject;

    @Lob
    private String matchSubjects;

    private double weight;
    private Integer questionCount;
    private int position;
}
