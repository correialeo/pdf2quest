package com.pdf2questao.block;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Bloco de estudo reutilizavel: um recorte de questoes (de uma ou varias provas) sem mexer no banco de questoes. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class StudyBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Lob
    private String description;

    private Integer timeLimitMinutes;
    private Long scoringProfileId;
    private LocalDateTime createdAt = LocalDateTime.now();
}
