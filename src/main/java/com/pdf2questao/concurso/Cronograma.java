package com.pdf2questao.concurso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Conjunto de tarefas do cronograma (ex.: "Pré-edital", "Reta final"), de um concurso ou avulso. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Cronograma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Concurso do cronograma; nulo = cronograma avulso. */
    private Long concursoId;

    @Column(nullable = false)
    private String nome;

    private LocalDate inicio;
    private LocalDate fim;

    private boolean ativo = true;

    private LocalDateTime createdAt = LocalDateTime.now();
}
