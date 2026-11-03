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

/**
 * Trecho LITERAL de uma fonte (lei, norma, documentacao), gravado pelo agente antes de escrever a aula: o texto
 * baixado e extraido (curl, pdftotext), nao o resumo de uma ferramenta de leitura. A aula cita o dispositivo a partir
 * dele e o mostra no slide de fontes.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class SourceExcerpt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long concursoId;
    /** Aula que usou o trecho (preenchida na criacao da aula). */
    private Long lessonId;
    private String url;
    /** Ex.: "LRF, art. 42", "Lei 4.320, art. 36", "PySpark: DataFrame.persist". */
    private String dispositivo;
    @Lob
    private String texto;
    /** Como o texto foi obtido (ex.: "curl + pdftotext do PDF do Planalto"). */
    private String comoObtido;
    private String sha256;
    private LocalDateTime createdAt = LocalDateTime.now();
}
