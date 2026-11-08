package com.pdf2questao.flashcard;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Cartao de recordacao de um conceito ({#id}) de uma aula: frente sem pista, verso curto. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Flashcard {

    public static final String PERGUNTA = "pergunta";
    public static final String LACUNA = "lacuna";
    public static final String CONTRASTE = "contraste";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long concursoId;
    private Long lessonId;
    private String concept;
    private String kind;
    @Lob
    private String front;
    @Lob
    private String back;
    private LocalDateTime createdAt = LocalDateTime.now();
}
