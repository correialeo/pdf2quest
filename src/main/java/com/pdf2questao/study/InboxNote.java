package com.pdf2questao.study;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Anotacao rapida da caixa de entrada: texto simples, organizado depois (vira pagina ou ganha disciplina). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class InboxNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Lob
    @Column(nullable = false)
    private String content;

    private String subject;

    private LocalDateTime createdAt = LocalDateTime.now();
}
