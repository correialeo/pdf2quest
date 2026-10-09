package com.pdf2questao.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Texto de apoio sem numeracao propria, compartilhado pelas questoes seguintes. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Passage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long importJobId;

    @Lob
    @Column(nullable = false)
    private String content;
}
