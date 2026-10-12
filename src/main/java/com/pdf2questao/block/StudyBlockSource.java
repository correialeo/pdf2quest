package com.pdf2questao.block;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Origem de questoes de um bloco. Com {@code importJobId}, pega a prova (limitada por
 * {@code ranges}, ex.: "1-10, 32-46"; em branco = prova toda). Sem ele, e um filtro por
 * disciplina/assunto/banca/ano sobre todas as provas. Filtros e faixas se combinam (E).
 * {@code maxQuestions} limita e {@code shuffle} embaralha a escolha dentro da origem.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class StudyBlockSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long blockId;
    private int position;
    private Long importJobId;
    private String ranges;
    private String subject;
    private String topic;
    private String organization;
    private Integer year;
    private Integer maxQuestions;
    private boolean shuffle;
}
