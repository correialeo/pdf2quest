package com.pdf2questao.question;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class QuestionImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long importJobId;
    private Integer page;

    // Sem @Lob: o driver do SQLite nao implementa leitura via java.sql.Blob.
    @Column(nullable = false, columnDefinition = "blob")
    private byte[] data;
}
