package com.pdf2questao.simulado;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Simulado montado pelo Claude. Enquanto {@code blockId} e nulo e um rascunho: as questoes vao entrando na prova
 * ({@code importJobId}) em lotes. Finalizar cria o bloco com o perfil de pontuacao e o tempo limite.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class GeneratedExam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Concurso (workspace) a que pertence; nulo = sem concurso. */
    private Long concursoId;

    private Long importJobId;
    private Long scoringProfileId;
    /** Prova do perfil que o simulado imita (ex.: "P1"); null = todas as provas do perfil. */
    private String groupName;
    private Integer timeLimitMinutes;
    /** Estilo, padrao e pegadinhas da banca que o Claude pesquisou antes de escrever (obrigatorio ao criar). */
    @jakarta.persistence.Lob
    private String styleNotes;
    /** Fontes da pesquisa (links), uma por linha. */
    @jakarta.persistence.Lob
    private String styleSources;
    private Long blockId;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime finishedAt;
}
