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

/** Quiz: ligado a uma aula (uma aula pode ter varios) ou avulso, sobre uma disciplina/topico. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Quiz {

    public static final String CERTO_ERRADO = "certo_errado";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Concurso (workspace) a que pertence; nulo = sem concurso. */
    private Long concursoId;

    /** Aula de origem; null = quiz avulso. */
    private Long lessonId;
    /** Tarefa do cronograma que o quiz cobre (opcional; sem ela vale a tarefa da aula). */
    private Long taskId;
    private String title;
    private String subject;
    private String topic;
    /** Nivel pedido: basico, intermediario, avancado, prova. */
    private String level;
    /** Formato: null = multipla escolha; {@link #CERTO_ERRADO} = itens Certo/Errado (Cebraspe). */
    private String format;
    /** Duvidas do usuario que o quiz deve trabalhar. */
    @Lob
    private String doubts;
    /** Erros de quizzes anteriores que este quiz retoma (resumo gravado na criacao). */
    @Lob
    private String previousErrors;
    /** Nivel prova: como a banca cobra o topico, observado nas questoes reais lidas. */
    @Lob
    private String styleNotes;
    /** Questoes reais lidas como modelo, uma por linha (link ou "questao local #id"). */
    @Lob
    private String styleSources;
    /** Questoes de fixacao no meio de uma aula em slides (respondidas na propria aula, nao numa tela de quiz). */
    private boolean embedded;
    /** Flashcards mostrados antes das questoes (ids separados por virgula, na ordem); nulo = sem cartoes. */
    private String flashcards;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime finishedAt;
    /** Refazer: respostas anteriores a isto saem da tela (continuam no historico). */
    private LocalDateTime restartedAt;

    public java.util.List<Long> flashcardIds() {
        return flashcards == null || flashcards.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(flashcards.split(",")).map(String::strip).filter(x -> !x.isEmpty())
                .map(Long::valueOf).toList();
    }

    public boolean certoErrado() {
        return CERTO_ERRADO.equals(format);
    }
}
