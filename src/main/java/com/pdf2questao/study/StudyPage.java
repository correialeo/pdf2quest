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

/** Pagina da area de Estudos (resumo, anotacao). Subpaginas apontam para a mae por {@link #parentId}. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class StudyPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Pagina-mae; nulo = pagina raiz. */
    private Long parentId;

    @Column(nullable = false)
    private String title;

    /** HTML ja limpo pelo {@link HtmlSanitizer}. */
    @Lob
    private String content;

    private String subject;

    /** Dia do cronograma que esta pagina resume; nulo = pagina comum. */
    private java.time.LocalDate summaryDate;

    /** Ordem entre as irmas. */
    private int position;

    /** Cresce a cada gravacao; o editor manda a versao que tem e o servidor recusa se outra aba gravou antes. */
    private int version;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Hora da ultima gravacao em milissegundos, para o editor saber se um rascunho local ja chegou ao servidor. */
    public long getUpdatedAtMillis() {
        return updatedAt == null ? 0 : updatedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public String getDisplayTitle() {
        return title == null || title.isBlank() ? "Sem título" : title;
    }
}
