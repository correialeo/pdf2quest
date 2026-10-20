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

/** Aula escrita pelo Claude (Markdown) sobre um topico do edital, com um quiz ligado a ela. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String subject;
    private String editalTopic;
    @Lob
    private String content;
    /** Tarefa do cronograma que a aula cobre (opcional). */
    private Long taskId;
    private LocalDateTime createdAt = LocalDateTime.now();
    /** Quando o quiz foi respondido inteiro. */
    private LocalDateTime finishedAt;
    /** Refazer o quiz: respostas anteriores a isto saem da tela (continuam no historico). */
    private LocalDateTime restartedAt;
}
