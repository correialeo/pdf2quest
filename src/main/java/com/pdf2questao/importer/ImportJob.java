package com.pdf2questao.importer;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private int importedQuestions;
    private int failedQuestions;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Lob
    private String errorLog;

    private String detectedOrganization;
    private Integer detectedYear;
    private String banca;

    private String pdfPath;

    /** Nome amigavel da prova, editavel; ver {@link #getDisplayName()}. */
    private String title;

    /** Relatorio da importacao (totais, questoes para revisar, divergencias com o gabarito). */
    @Lob
    private String report;

    /** Titulo da prova, ou o nome do arquivo (sem ".pdf") enquanto ninguem deu um titulo. */
    public String getDisplayName() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (fileName == null) {
            return "Prova " + id;
        }
        return fileName.replaceAll("(?i)\\.pdf$", "");
    }
}
