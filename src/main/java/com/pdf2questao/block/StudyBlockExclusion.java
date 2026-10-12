package com.pdf2questao.block;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Questao fora do escopo de um bloco (a questao continua no banco). Ou {@code questionId}, ou
 * {@code questionNumber} (numero na prova), opcionalmente preso a uma prova por {@code importJobId}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class StudyBlockExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long blockId;
    private Long questionId;
    private Long importJobId;
    private Integer questionNumber;
    private String topic;
}
