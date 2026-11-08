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

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Tarefa do cronograma de estudos. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class StudyTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Lob
    private String description;

    private String subject;

    /** Dia planejado; nulo = sem data. */
    private LocalDate plannedDate;

    private boolean done;
    private LocalDateTime doneAt;

    private Integer estimatedMinutes;
    private Integer spentMinutes;

    /** Pagina de resumo ligada a tarefa (opcional). Apagar a pagina so desfaz o vinculo. */
    private Long pageId;

    /** Cronograma da tarefa; nulo = tarefa avulsa. O concurso vem do cronograma. */
    private Long cronogramaId;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Revisao espacada: indice em {@link SpacedRepetitionService#intervals()} da proxima revisao bem-sucedida. */
    @Column(nullable = false)
    private int currentStep;

    /** Dia da proxima revisao; nulo = ainda nao agendada. */
    private LocalDate nextReviewDate;

    /** Preenchido so na tarefa diaria de revisao: o dia em que foi montada. Ela nao entra no ciclo de revisao. */
    private LocalDate reviewDate;

    /** Falso: concluir a tarefa nao agenda revisao espacada (descanso, prova, tarefas que nao sao assunto). */
    private boolean reviewEnabled = true;

    /** So na tarefa diaria de revisao: ids dos assuntos escolhidos para o dia (foco), separados por virgula. */
    @Column(length = 2000)
    private String reviewItems;

    public boolean isReview() {
        return reviewDate != null;
    }

    /** Assuntos da tarefa de revisao, na ordem em que foram escolhidos. */
    public java.util.List<Long> reviewItemIds() {
        if (reviewItems == null || reviewItems.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(reviewItems.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
    }

    /** Cabecalho de bloco vindo do .docx ("B – TI (90 min)"): so repete disciplina e tempo, ja mostrados na lista. */
    private static final java.util.regex.Pattern BLOCK_ONLY =
            java.util.regex.Pattern.compile("^\\S{1,4} – [^\\n]{1,40}\\(\\d+ min\\)$");

    /** Descricao para a lista do cronograma: nula quando e so o cabecalho do bloco importado; a revisao vira uma linha. */
    public String getListDescription() {
        if (isReview() && description != null) {
            return description.replace("- ", "").replace("\n", " · ");
        }
        return description == null || BLOCK_ONLY.matcher(description.strip()).matches() ? null : description;
    }

    public boolean isOverdue() {
        return !done && plannedDate != null && plannedDate.isBefore(LocalDate.now());
    }
}
