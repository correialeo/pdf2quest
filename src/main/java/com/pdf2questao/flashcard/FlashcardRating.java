package com.pdf2questao.flashcard;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Autoavaliacao de um cartao: errei, dificil ou facil (a ultima de cada cartao no quiz e a que vale). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class FlashcardRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long flashcardId;
    private Long quizId;
    private String rating;
    private LocalDateTime ratedAt = LocalDateTime.now();
}
