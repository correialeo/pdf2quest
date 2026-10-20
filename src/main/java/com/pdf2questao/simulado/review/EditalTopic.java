package com.pdf2questao.simulado.review;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Item do conteudo programatico do edital, por disciplina do perfil de pontuacao. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class EditalTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long scoringProfileId;
    private String subject;
    private String topic;
    private Integer position;
}
