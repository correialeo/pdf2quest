package com.pdf2questao.aula;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Quiz: ligado a uma aula (uma aula pode ter varios) ou avulso, sobre uma disciplina/topico. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Aula de origem; null = quiz avulso. */
    private Long lessonId;
    private String title;
    private String subject;
    private String topic;
    /** Nivel pedido: basico, intermediario, avancado, prova. */
    private String level;
    /** Duvidas do usuario que o quiz deve trabalhar. */
    @Lob
    private String doubts;
    /** Erros de quizzes anteriores que este quiz retoma (resumo gravado na criacao). */
    @Lob
    private String previousErrors;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime finishedAt;
    /** Refazer: respostas anteriores a isto saem da tela (continuam no historico). */
    private LocalDateTime restartedAt;
}
