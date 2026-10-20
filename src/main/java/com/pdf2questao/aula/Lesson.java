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

/** Aula escrita pelo Claude (Markdown) sobre um topico do edital; pode ter varios quizzes. */
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
    /** Legado (V16): o estado do quiz agora fica em {@link Quiz}. */
    private LocalDateTime finishedAt;
    private LocalDateTime restartedAt;
}
