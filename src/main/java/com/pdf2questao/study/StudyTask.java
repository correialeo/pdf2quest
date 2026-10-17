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

    private LocalDateTime createdAt = LocalDateTime.now();

    /** Revisao espacada: indice em {@link SpacedRepetitionService#intervals()} da proxima revisao bem-sucedida. */
    @Column(nullable = false)
    private int currentStep;

    /** Dia da proxima revisao; nulo = ainda nao agendada. */
    private LocalDate nextReviewDate;

    /** Preenchido so na tarefa diaria de revisao: o dia em que foi montada. Ela nao entra no ciclo de revisao. */
    private LocalDate reviewDate;

    public boolean isReview() {
        return reviewDate != null;
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
